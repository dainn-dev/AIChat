/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

/** How the mic key gets speech into the field. */
enum class VoiceInputRoute {
    /** Switch to an enabled voice-typing IME (e.g. Google voice typing). */
    VOICE_IME,

    /**
     * A trusted speech screen (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH` pinned to a system app or the default
     * recognition service's app, see [RecognizerTrust]); it holds the mic permission itself.
     */
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

    /**
     * True when the mic key must not start: no field, a private field (password, `TYPE_NULL`), a password
     * keyboard layout, or incognito mode. Speech goes through a recognizer (often a cloud service), so text that
     * must stay private never takes that path.
     */
    fun isBlocked(field: VoiceField?, keyboardPassword: Boolean, incognito: Boolean): Boolean =
        field == null || field.isPrivate || keyboardPassword || incognito

    /** [spoken] as committed after [textBeforeCursor]: a space keeps it from gluing onto the previous word. */
    fun textToCommit(textBeforeCursor: CharSequence?, spoken: String): String =
        if (!textBeforeCursor.isNullOrEmpty() && !textBeforeCursor.last().isWhitespace()) " $spoken" else spoken

    private const val DEFAULT_TAG = "en-US"
    private val DefaultRegions = mapOf("vi" to "VN", "en" to "US")
}

/**
 * Recognized text waiting for its field. The trampoline activity takes the focus from the chat app, so the text
 * can only be committed when the keyboard is bound to that app's field again (next `onStartInput`). It is
 * committed at most once, only into the exact field it was spoken for ([VoiceField.matches]), never into a private
 * field, and only within [TTL_MS]. Any other field bound in the meantime drops it, except fields of the keyboard's
 * own app (the speech screen itself).
 */
class PendingVoiceText {
    private var text: String? = null
    private var target: VoiceField? = null
    private var offeredAtMs = 0L

    @Synchronized
    fun offer(text: String, target: VoiceField?, nowMs: Long) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || target == null || target.packageName.isNullOrEmpty() || target.isPrivate) {
            clear()
            return
        }
        this.text = trimmed
        this.target = target
        offeredAtMs = nowMs
    }

    /** The text to commit into [field], or null. */
    @Synchronized
    fun consume(field: VoiceField?, nowMs: Long, ownPackage: String? = null): String? {
        val pending = text ?: return null
        val expected = target ?: return null
        if (nowMs - offeredAtMs > TTL_MS) {
            clear()
            return null
        }
        if (field == null) return null
        if (ownPackage != null && field.packageName == ownPackage) return null
        clear()
        return pending.takeIf { !field.isPrivate && field.matches(expected) }
    }

    @Synchronized
    fun clear() {
        text = null
        target = null
    }

    companion object {
        /** Long enough for the chat app to get its focus back, short enough not to surprise the user later. */
        const val TTL_MS = 15_000L
    }
}
