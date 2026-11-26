/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R

/**
 * Small transparent activity behind the mic key when no voice IME is enabled. An IME cannot show the mic
 * permission dialog or start an activity for a result, so this does both: it opens the system speech screen
 * ([VoiceInputRoute.RECOGNIZER_ACTIVITY]) or asks for RECORD_AUDIO and listens with [SpeechRecognizer]
 * ([VoiceInputRoute.SPEECH_RECOGNIZER]), hands the text to [VoiceInput] and closes. The chat app gets its focus
 * back and the keyboard commits the text. Not exported. Recognized text is never logged.
 */
class VoiceInputActivity : ComponentActivity() {
    private val recognizerScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            deliver(result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS))
        }
        finish()
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            startListening()
        } else {
            VoiceInput.showMessage(this, R.string.aichat__voice__permission_denied)
            finish()
        }
    }

    private var recognizer: SpeechRecognizer? = null
    private var partialText by mutableStateOf("")

    private val languageTag: String get() = intent.getStringExtra(EXTRA_LANGUAGE) ?: DEFAULT_LANGUAGE
    private val targetPackage: String? get() = intent.getStringExtra(EXTRA_TARGET_PACKAGE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            // Recreated (e.g. rotation) after the recognizer was lost: start over from the keyboard.
            finish()
            return
        }
        when (intent.getStringExtra(EXTRA_ROUTE)) {
            VoiceInputRoute.RECOGNIZER_ACTIVITY.name -> openRecognizerScreen()
            VoiceInputRoute.SPEECH_RECOGNIZER.name -> {
                setContent { ListeningSheet(partialText, onStop = ::stopListening, onCancel = ::cancel) }
                if (hasMicPermission()) startListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // The user left (home, another app): never keep the mic open in the background.
        if (!isChangingConfigurations && recognizer != null) cancel()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    private fun openRecognizerScreen() {
        val intent = recognitionIntent()
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.aichat__voice__prompt))
        try {
            recognizerScreen.launch(intent)
        } catch (_: ActivityNotFoundException) {
            VoiceInput.showMessage(this, R.string.aichat__voice__unavailable)
            finish()
        }
    }

    private fun startListening() {
        val speech = createRecognizer() ?: run {
            VoiceInput.showMessage(this, R.string.aichat__voice__unavailable)
            finish()
            return
        }
        recognizer = speech
        speech.setRecognitionListener(Listener())
        speech.startListening(recognitionIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true))
    }

    private fun createRecognizer(): SpeechRecognizer? = runCatching {
        when {
            SpeechRecognizer.isRecognitionAvailable(this) -> SpeechRecognizer.createSpeechRecognizer(this)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && VoiceInput.hasOnDeviceRecognizer(this) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            else -> null
        }
    }.getOrNull()

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Tap on the sheet: stop recording and recognize what was said so far. */
    private fun stopListening() {
        recognizer?.stopListening()
    }

    private fun cancel() {
        recognizer?.cancel()
        finish()
    }

    private fun deliver(results: List<String>?) {
        val text = VoiceInputRouting.bestResult(results)
        if (text == null) {
            VoiceInput.showMessage(this, R.string.aichat__voice__no_match)
        } else {
            VoiceInput.offerResult(text, targetPackage)
        }
    }

    private inner class Listener : RecognitionListener {
        override fun onResults(results: Bundle?) {
            deliver(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
            finish()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            VoiceInputRouting.bestResult(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION))
                ?.let { partialText = it }
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.aichat__voice__no_match
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.aichat__voice__error_network
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.aichat__voice__permission_denied
                else -> R.string.aichat__voice__error
            }
            VoiceInput.showMessage(this@VoiceInputActivity, message)
            finish()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        private const val EXTRA_ROUTE = "com.aichat.keyboard.voice.ROUTE"
        private const val EXTRA_LANGUAGE = "com.aichat.keyboard.voice.LANGUAGE"
        private const val EXTRA_TARGET_PACKAGE = "com.aichat.keyboard.voice.TARGET_PACKAGE"
        private const val DEFAULT_LANGUAGE = "en-US"

        /** Opened from the keyboard (a service context, hence the new task). */
        fun launch(context: Context, route: VoiceInputRoute, languageTag: String, targetPackage: String?) {
            val intent = Intent(context, VoiceInputActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ROUTE, route.name)
                .putExtra(EXTRA_LANGUAGE, languageTag)
                .putExtra(EXTRA_TARGET_PACKAGE, targetPackage)
            context.startActivity(intent)
        }
    }
}

private val SheetColor = Color(0xF2202124)
private val SheetTextColor = Color.White
private val SheetHintColor = Color(0xB3FFFFFF)
private val MicColor = Color(0xFFFF453A)

/** Bottom sheet while listening: tap it to finish speaking, tap outside to cancel. */
@Composable
private fun ListeningSheet(partialText: String, onStop: () -> Unit, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(16.dp)
                .fillMaxWidth()
                .background(SheetColor, RoundedCornerShape(20.dp))
                .clickable(onClick = onStop)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = MicColor, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = partialText.ifEmpty { stringResource(R.string.aichat__voice__listening) },
                color = SheetTextColor,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.aichat__voice__tap_to_finish),
                color = SheetHintColor,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
