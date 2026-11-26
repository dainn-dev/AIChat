/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

/** How the mic key gets speech into the field. */
enum class VoiceInputRoute {
    /** Switch to an enabled voice-typing IME (e.g. Google voice typing). */
    VOICE_IME,

    /** The system speech screen (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`); it holds the mic permission itself. */
    RECOGNIZER_ACTIVITY,

    /** A bare `SpeechRecognizer` service, driven from our trampoline activity (needs RECORD_AUDIO). */
    SPEECH_RECOGNIZER,

    /** Nothing on the device can do speech-to-text: say so instead of doing nothing. */
    UNAVAILABLE,
}

/** Pure decisions behind the mic key. Unit-tested. */
object VoiceInputRouting {
    fun choose(hasVoiceIme: Boolean, hasRecognizerActivity: Boolean, hasSpeechRecognizer: Boolean): VoiceInputRoute =
        when {
            hasVoiceIme -> VoiceInputRoute.VOICE_IME
            hasRecognizerActivity -> VoiceInputRoute.RECOGNIZER_ACTIVITY
            hasSpeechRecognizer -> VoiceInputRoute.SPEECH_RECOGNIZER
            else -> VoiceInputRoute.UNAVAILABLE
        }

    /**
     * BCP 47 tag for the recognizer from the keyboard subtype's locale. Subtypes are often language-only (`vi`,
     * `en`); recognizers work best with a region, so the common ones get their main region.
     */
    fun speechLanguageTag(language: String, country: String): String = when {
        language.isBlank() -> DEFAULT_TAG
        country.isNotBlank() -> "$language-$country"
        else -> DefaultRegions[language]?.let { "$language-$it" } ?: language
    }

    /** The first usable recognition result, trimmed; recognizers return the best guess first. */
    fun bestResult(results: List<String>?): String? =
        results?.firstNotNullOfOrNull { it.trim().takeIf(String::isNotEmpty) }

    /** [spoken] as committed after [textBeforeCursor]: a space keeps it from gluing onto the previous word. */
    fun textToCommit(textBeforeCursor: CharSequence?, spoken: String): String =
        if (!textBeforeCursor.isNullOrEmpty() && !textBeforeCursor.last().isWhitespace()) " $spoken" else spoken

    private const val DEFAULT_TAG = "en-US"
    private val DefaultRegions = mapOf("vi" to "VN", "en" to "US")
}

/**
 * Recognized text waiting for its field. The trampoline activity takes the focus from the chat app, so the text
 * can only be committed when the keyboard is bound to that app's field again (next `onStartInput`). It is
 * committed at most once, only into [targetPackage], and only within [TTL_MS].
 */
class PendingVoiceText {
    private var text: String? = null
    private var targetPackage: String? = null
    private var offeredAtMs = 0L

    @Synchronized
    fun offer(text: String, targetPackage: String?, nowMs: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || targetPackage.isNullOrEmpty()) {
            clear()
            return
        }
        this.text = trimmed
        this.targetPackage = targetPackage
        offeredAtMs = nowMs
    }

    /** The text to commit into a field of [packageName], or null. */
    @Synchronized
    fun consume(packageName: String?, nowMs: Long): String? {
        val pending = text ?: return null
        if (nowMs - offeredAtMs > TTL_MS) {
            clear()
            return null
        }
        if (packageName == null || packageName != targetPackage) return null
        clear()
        return pending
    }

    @Synchronized
    fun clear() {
        text = null
        targetPackage = null
    }

    companion object {
        /** Long enough for the chat app to get its focus back, short enough not to surprise the user later. */
        const val TTL_MS = 15_000L
    }
}
