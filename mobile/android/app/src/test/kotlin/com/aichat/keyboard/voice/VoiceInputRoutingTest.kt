package com.aichat.keyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceInputRoutingTest {
    @Test
    fun `an enabled voice IME wins`() {
        assertEquals(
            VoiceInputRoute.VOICE_IME,
            VoiceInputRouting.choose(hasVoiceIme = true, hasRecognizerActivity = true, hasSpeechRecognizer = true),
        )
    }

    @Test
    fun `without a voice IME the system recognizer screen is used (no mic permission needed)`() {
        assertEquals(
            VoiceInputRoute.RECOGNIZER_ACTIVITY,
            VoiceInputRouting.choose(hasVoiceIme = false, hasRecognizerActivity = true, hasSpeechRecognizer = true),
        )
    }

    @Test
    fun `then a bare recognition service`() {
        assertEquals(
            VoiceInputRoute.SPEECH_RECOGNIZER,
            VoiceInputRouting.choose(hasVoiceIme = false, hasRecognizerActivity = false, hasSpeechRecognizer = true),
        )
    }

    @Test
    fun `nothing available is reported, not silently ignored`() {
        assertEquals(
            VoiceInputRoute.UNAVAILABLE,
            VoiceInputRouting.choose(hasVoiceIme = false, hasRecognizerActivity = false, hasSpeechRecognizer = false),
        )
    }

    @Test
    fun `speech language follows the keyboard subtype`() {
        assertEquals("vi-VN", VoiceInputRouting.speechLanguageTag(language = "vi", country = ""))
        assertEquals("en-US", VoiceInputRouting.speechLanguageTag(language = "en", country = ""))
        assertEquals("en-GB", VoiceInputRouting.speechLanguageTag(language = "en", country = "GB"))
        assertEquals("fr", VoiceInputRouting.speechLanguageTag(language = "fr", country = ""))
        assertEquals("en-US", VoiceInputRouting.speechLanguageTag(language = "", country = ""))
    }

    @Test
    fun `dictated text is separated from the word before the cursor`() {
        assertEquals(" xin chào", VoiceInputRouting.textToCommit(textBeforeCursor = "Anh", spoken = "xin chào"))
        assertEquals("xin chào", VoiceInputRouting.textToCommit(textBeforeCursor = "Anh ", spoken = "xin chào"))
        assertEquals("xin chào", VoiceInputRouting.textToCommit(textBeforeCursor = "Anh\n", spoken = "xin chào"))
        assertEquals("xin chào", VoiceInputRouting.textToCommit(textBeforeCursor = "", spoken = "xin chào"))
        assertEquals("xin chào", VoiceInputRouting.textToCommit(textBeforeCursor = null, spoken = "xin chào"))
    }

    @Test
    fun `the first non-blank recognition result is used, trimmed`() {
        assertEquals("xin chào", VoiceInputRouting.bestResult(listOf("  ", " xin chào ", "xin chao")))
        assertNull(VoiceInputRouting.bestResult(listOf("", " ")))
        assertNull(VoiceInputRouting.bestResult(null))
    }
}
