/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import dev.patrickgold.florisboard.R
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

    private val pending = PendingVoiceText()

    /** Mic key pressed. Returns true when voice input was started. */
    fun start(ims: InputMethodService): Boolean {
        val voiceIme = findVoiceIme(ims)
        val route = VoiceInputRouting.choose(
            hasVoiceIme = voiceIme != null,
            hasRecognizerActivity = hasRecognizerActivity(ims),
            hasSpeechRecognizer = hasSpeechRecognizer(ims),
        )
        Log.i(TAG, "Mic key: route=$route")
        return when (route) {
            VoiceInputRoute.VOICE_IME -> switchToVoiceIme(ims, voiceIme!!) || startRecognition(ims, fallbackRoute(ims))
            // startRecognition() reports UNAVAILABLE to the user.
            else -> startRecognition(ims, route)
        }
    }

    /** Called by the IME for every newly bound field; commits recognized text waiting for this app. */
    fun onStartInput(ims: InputMethodService, info: EditorInfo) {
        val text = pending.consume(info.packageName, System.currentTimeMillis()) ?: return
        val ic = ims.currentInputConnection ?: return
        ic.commitText(VoiceInputRouting.textToCommit(ic.getTextBeforeCursor(1, 0), text), 1)
    }

    /** From [VoiceInputActivity]: the text to commit into [targetPackage]'s field. Never logged. */
    internal fun offerResult(text: String, targetPackage: String?) {
        pending.offer(text, targetPackage, System.currentTimeMillis())
    }

    internal fun showMessage(context: Context, messageRes: Int) {
        Toast.makeText(context.applicationContext, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun startRecognition(ims: InputMethodService, route: VoiceInputRoute): Boolean {
        if (route != VoiceInputRoute.RECOGNIZER_ACTIVITY && route != VoiceInputRoute.SPEECH_RECOGNIZER) {
            showMessage(ims, R.string.aichat__voice__unavailable)
            return false
        }
        pending.clear()
        val locale = ims.subtypeManager().value.activeSubtype.primaryLocale.base
        val languageTag = VoiceInputRouting.speechLanguageTag(locale.language, locale.country)
        return runCatching {
            VoiceInputActivity.launch(ims, route, languageTag, ims.currentInputEditorInfo?.packageName)
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

    private fun hasRecognizerActivity(context: Context): Boolean = runCatching {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }.getOrDefault(false)

    internal fun hasSpeechRecognizer(context: Context): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context) || hasOnDeviceRecognizer(context)
    }.getOrDefault(false)

    internal fun hasOnDeviceRecognizer(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
}
