/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager

/**
 * The mic key. Upstream FlorisBoard only switches to an enabled voice-typing IME and otherwise shows an English
 * toast, so on phones without Google voice typing enabled the key looked dead. This switches to a voice IME when
 * there is one, otherwise runs Android speech recognition from [VoiceInputActivity] and commits the text into the
 * field when the keyboard is bound to it again ([onStartInput]); with no recognizer at all it says so.
 */
object VoiceInput {
    private const val TAG = "VoiceInput"
    private const val VOICE_SUBTYPE_MODE = "voice"

    /** `Settings.Secure.VOICE_RECOGNITION_SERVICE` (hidden API): the default `RecognitionService` component. */
    private const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"

    private val pending = PendingVoiceText()

    /** The field the mic key was last pressed in; the recognized text may only go back there. */
    @Volatile
    private var targetField: VoiceField? = null

    /** Mic key pressed. Returns true when voice input was started. */
    fun start(ims: InputMethodService): Boolean {
        val field = VoiceField.of(ims.currentInputEditorInfo)
        if (isBlocked(ims, field)) {
            Log.i(TAG, "Mic key: blocked in a private field or incognito mode")
            showMessage(ims, R.string.aichat__voice__blocked_private)
            return false
        }
        val voiceIme = findVoiceIme(ims)
        val route = VoiceInputRouting.choose(
            hasVoiceIme = voiceIme != null,
            hasRecognizerActivity = hasRecognizerActivity(ims),
            hasSpeechRecognizer = hasSpeechRecognizer(ims),
        )
        Log.i(TAG, "Mic key: route=$route")
        return when (route) {
            VoiceInputRoute.VOICE_IME -> switchToVoiceIme(ims, voiceIme!!) ||
                startRecognition(ims, fallbackRoute(ims), field)
            // startRecognition() reports UNAVAILABLE to the user.
            else -> startRecognition(ims, route, field)
        }
    }

    /** Called by the IME for every newly bound field; commits recognized text waiting for exactly this field. */
    fun onStartInput(ims: InputMethodService, info: EditorInfo) {
        val text = pending.consume(VoiceField.of(info), System.currentTimeMillis(), ownPackage = ims.packageName)
            ?: return
        val ic = ims.currentInputConnection ?: return
        ic.commitText(VoiceInputRouting.textToCommit(ic.getTextBeforeCursor(1, 0), text), 1)
    }

    /** From [VoiceInputActivity]: the text to commit into the field the mic key was pressed in. Never logged. */
    internal fun offerResult(text: String) {
        pending.offer(text, targetField, System.currentTimeMillis())
    }

    internal fun showMessage(context: Context, messageRes: Int) {
        Toast.makeText(context.applicationContext, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun isBlocked(ims: InputMethodService, field: VoiceField?): Boolean {
        val state = runCatching { ims.keyboardManager().value.activeState }.getOrNull()
        return VoiceInputRouting.isBlocked(
            field = field,
            keyboardPassword = state?.keyVariation == KeyVariation.PASSWORD,
            // Unknown keyboard state: assume private rather than send speech out.
            incognito = state?.isIncognitoMode ?: true,
        )
    }

    private fun startRecognition(ims: InputMethodService, route: VoiceInputRoute, field: VoiceField?): Boolean {
        if (route != VoiceInputRoute.RECOGNIZER_ACTIVITY && route != VoiceInputRoute.SPEECH_RECOGNIZER) {
            showMessage(ims, R.string.aichat__voice__unavailable)
            return false
        }
        pending.clear()
        targetField = field
        val locale = ims.subtypeManager().value.activeSubtype.primaryLocale.base
        val languageTag = VoiceInputRouting.speechLanguageTag(locale.language, locale.country)
        return runCatching {
            VoiceInputActivity.launch(ims, route, languageTag)
        }.onFailure { e ->
            Log.w(TAG, "Cannot open the speech screen: ${e.javaClass.simpleName}")
            showMessage(ims, R.string.aichat__voice__unavailable)
        }.isSuccess
    }

    private fun fallbackRoute(context: Context) = VoiceInputRouting.choose(
        hasVoiceIme = false,
        hasRecognizerActivity = hasRecognizerActivity(context),
        hasSpeechRecognizer = hasSpeechRecognizer(context),
    )

    private fun findVoiceIme(ims: InputMethodService): Pair<InputMethodInfo, InputMethodSubtype>? = runCatching {
        val imm = ims.getSystemService(InputMethodManager::class.java) ?: return@runCatching null
        imm.enabledInputMethodList
            .asSequence()
            .filter { it.packageName != ims.packageName }
            .flatMap { info -> (0 until info.subtypeCount).asSequence().map { info to info.getSubtypeAt(it) } }
            .firstOrNull { (_, subtype) -> subtype.mode == VOICE_SUBTYPE_MODE }
    }.getOrNull()

    private fun switchToVoiceIme(ims: InputMethodService, ime: Pair<InputMethodInfo, InputMethodSubtype>): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ims.switchInputMethod(ime.first.id, ime.second)
            } else {
                val token = ims.window.window?.attributes?.token ?: return@runCatching false
                @Suppress("DEPRECATION")
                ims.getSystemService(InputMethodManager::class.java)?.setInputMethod(token, ime.first.id)
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "Cannot switch to the voice IME: ${e.javaClass.simpleName}")
            false
        }

    /** Only a trusted speech screen counts: any app can answer the implicit intent and return arbitrary text. */
    private fun hasRecognizerActivity(context: Context): Boolean = trustedRecognizerPackage(context) != null

    /**
     * Package of a speech screen whose text may be committed ([RecognizerTrust]): the default recognition
     * service's app or a system app. Null when there is none (the caller then uses [SpeechRecognizer], which binds
     * to the system default service).
     */
    internal fun trustedRecognizerPackage(context: Context): String? = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val candidates = pm.queryIntentActivities(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).mapNotNull { info ->
            val app = info.activityInfo?.applicationInfo ?: return@mapNotNull null
            val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            RecognizerCandidate(app.packageName, isSystemApp = app.flags and systemFlags != 0)
        }
        val defaultService = Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE_SETTING)
        RecognizerTrust.pickTrusted(candidates, RecognizerTrust.packageOfComponent(defaultService))
    }.getOrNull()

    /** The selected default recognition service (`pkg/cls`), or null when the system has none (some vivo phones). */
    internal fun defaultRecognitionService(context: Context): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, VOICE_RECOGNITION_SERVICE_SETTING)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** A trusted recognition service to bind explicitly when there is no default one ([RecognizerTrust.pickService]). */
    internal fun trustedRecognitionService(context: Context): ComponentName? = runCatching {
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        @Suppress("DEPRECATION")
        val candidates = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                ServiceCandidate(
                    packageName = service.packageName,
                    component = ComponentName(service.packageName, service.name).flattenToString(),
                    isSystemApp = service.applicationInfo.flags and systemFlags != 0,
                )
            }
        RecognizerTrust.pickService(candidates)?.let(ComponentName::unflattenFromString)
    }.getOrNull()

    internal fun hasSpeechRecognizer(context: Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context) || hasOnDeviceRecognizer(context)
    }.getOrDefault(false)

    internal fun hasOnDeviceRecognizer(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
}
