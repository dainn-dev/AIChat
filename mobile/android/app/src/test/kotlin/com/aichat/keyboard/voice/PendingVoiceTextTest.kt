package com.aichat.keyboard.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingVoiceTextTest {
    private val chatField = VoiceField(
        packageName = "com.zing.zalo",
        fieldId = 42,
        fieldName = "message_input",
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        imeOptions = EditorInfo.IME_ACTION_SEND,
    )

    @Test
    fun `recognized text goes back to the field it was spoken for, once`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = chatField, nowMs = 1_000L)
        assertEquals("hello", pending.consume(field = chatField, nowMs = 2_000L))
        assertNull(pending.consume(field = chatField, nowMs = 2_001L))
    }

    @Test
    fun `text is never committed into another app and is then dropped`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = chatField, nowMs = 1_000L)
        assertNull(pending.consume(field = chatField.copy(packageName = "com.facebook.orca"), nowMs = 2_000L))
        // Dropped, not kept for a later field.
        assertNull(pending.consume(field = chatField, nowMs = 3_000L))
    }

    @Test
    fun `text is never committed into another field of the same app`() {
        val others = listOf(
            chatField.copy(fieldId = 43),
            chatField.copy(fieldName = "search"),
            chatField.copy(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
            chatField.copy(inputType = InputType.TYPE_CLASS_NUMBER),
            chatField.copy(imeOptions = EditorInfo.IME_ACTION_SEARCH),
        )
        for (other in others) {
            val pending = PendingVoiceText()
            pending.offer(text = "hello", target = chatField, nowMs = 1_000L)
            assertNull(pending.consume(field = other, nowMs = 2_000L), "$other")
            assertNull(pending.consume(field = chatField, nowMs = 2_001L), "dropped after $other")
        }
    }

    @Test
    fun `input type flags and ime option flags do not break the match`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = chatField, nowMs = 1_000L)
        val sameFieldOtherFlags = chatField.copy(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI,
        )
        assertEquals("hello", pending.consume(field = sameFieldOtherFlags, nowMs = 2_000L))
    }

    @Test
    fun `fields of the keyboard's own app are ignored while waiting`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = chatField, nowMs = 1_000L)
        val ownScreen = VoiceField("com.aichat.keyboard", 0, null, InputType.TYPE_NULL, 0)
        assertNull(pending.consume(field = ownScreen, nowMs = 1_500L, ownPackage = "com.aichat.keyboard"))
        assertEquals("hello", pending.consume(field = chatField, nowMs = 2_000L, ownPackage = "com.aichat.keyboard"))
    }

    @Test
    fun `text is never committed into a password field even with a matching fingerprint`() {
        val password = chatField.copy(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = password, nowMs = 1_000L)
        assertNull(pending.consume(field = password, nowMs = 2_000L))
    }

    @Test
    fun `stale text expires`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = chatField, nowMs = 1_000L)
        assertNull(pending.consume(field = chatField, nowMs = 1_000L + PendingVoiceText.TTL_MS + 1))
    }

    @Test
    fun `an unknown target field or blank text is not kept`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", target = null, nowMs = 1_000L)
        assertNull(pending.consume(field = chatField, nowMs = 1_000L))
        pending.offer(text = "hello", target = chatField.copy(packageName = null), nowMs = 1_000L)
        assertNull(pending.consume(field = chatField.copy(packageName = null), nowMs = 1_000L))
        pending.offer(text = "  ", target = chatField, nowMs = 1_000L)
        assertNull(pending.consume(field = chatField, nowMs = 1_000L))
    }
}
