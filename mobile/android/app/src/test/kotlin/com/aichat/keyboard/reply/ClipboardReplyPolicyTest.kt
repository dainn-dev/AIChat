package com.aichat.keyboard.reply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardReplyPolicyTest {
    private val now = 1_000_000_000L

    private fun clip(
        text: String? = "Tối nay đi ăn không?",
        ageMs: Long = 10_000L,
        isSensitive: Boolean = false,
        isPlainText: Boolean = true,
    ) = ClipSnapshot(text = text, timestampMs = now - ageMs, isSensitive = isSensitive, isPlainText = isPlainText)

    @Test
    fun `a recent plain text clip becomes the partner message, trimmed`() {
        assertEquals("Hi there", ClipboardReplyPolicy.messageFrom(clip(text = "  Hi there \n"), now, fieldText = ""))
    }

    @Test
    fun `no clip, no text, or a non-text clip gives nothing`() {
        assertNull(ClipboardReplyPolicy.messageFrom(null, now, fieldText = ""))
        assertNull(ClipboardReplyPolicy.messageFrom(clip(text = null), now, fieldText = ""))
        assertNull(ClipboardReplyPolicy.messageFrom(clip(text = "   "), now, fieldText = ""))
        assertNull(ClipboardReplyPolicy.messageFrom(clip(isPlainText = false), now, fieldText = ""))
    }

    @Test
    fun `clips marked sensitive are ignored`() {
        assertNull(ClipboardReplyPolicy.messageFrom(clip(isSensitive = true), now, fieldText = ""))
    }

    @Test
    fun `clips older than the max age are ignored`() {
        val max = ClipboardReplyPolicy.MAX_AGE_MS
        assertEquals("Tối nay đi ăn không?", ClipboardReplyPolicy.messageFrom(clip(ageMs = max), now, fieldText = ""))
        assertNull(ClipboardReplyPolicy.messageFrom(clip(ageMs = max + 1), now, fieldText = ""))
    }

    @Test
    fun `clips from the future (clock change or unknown time base) are ignored beyond a small skew`() {
        assertEquals("Tối nay đi ăn không?", ClipboardReplyPolicy.messageFrom(clip(ageMs = -500L), now, fieldText = ""))
        assertNull(ClipboardReplyPolicy.messageFrom(clip(ageMs = -ClipboardReplyPolicy.MAX_FUTURE_SKEW_MS - 1), now, ""))
    }

    @Test
    fun `a clip equal to the text already in the field is ignored (the user copied their own draft)`() {
        assertNull(ClipboardReplyPolicy.messageFrom(clip(text = "Hello"), now, fieldText = "  Hello "))
        assertEquals("Hello", ClipboardReplyPolicy.messageFrom(clip(text = "Hello"), now, fieldText = "Hello you"))
        assertEquals("Hello", ClipboardReplyPolicy.messageFrom(clip(text = "Hello"), now, fieldText = null))
    }

    @Test
    fun `long clips are capped at the backend message limit`() {
        val long = "a".repeat(ClipboardReplyPolicy.MAX_MESSAGE_LENGTH + 50)
        assertEquals(ClipboardReplyPolicy.MAX_MESSAGE_LENGTH, ClipboardReplyPolicy.messageFrom(clip(text = long), now, "")?.length)
    }

    @Test
    fun `the cap never splits a surrogate pair`() {
        val text = "a".repeat(ClipboardReplyPolicy.MAX_MESSAGE_LENGTH - 1) + "😀" + "b"
        val capped = ClipboardReplyPolicy.messageFrom(clip(text = text), now, "")!!
        assertEquals(ClipboardReplyPolicy.MAX_MESSAGE_LENGTH - 1, capped.length)
        assertEquals(false, Character.isHighSurrogate(capped.last()))
    }

    @Test
    fun `the keyboard's own clip wins when it matches the system clip, otherwise the system clip is newer`() {
        val floris = clip(text = "same", ageMs = 1_000L)
        val systemSame = clip(text = "same", ageMs = 99_999_999L)
        assertEquals(floris, ClipboardReplyPolicy.pick(floris, systemSame))

        val systemNewer = clip(text = "new copy", ageMs = 2_000L)
        assertEquals(systemNewer, ClipboardReplyPolicy.pick(floris, systemNewer))

        assertEquals(floris, ClipboardReplyPolicy.pick(floris, null))
        assertEquals(systemNewer, ClipboardReplyPolicy.pick(null, systemNewer))
        assertNull(ClipboardReplyPolicy.pick(null, null))
    }
}
