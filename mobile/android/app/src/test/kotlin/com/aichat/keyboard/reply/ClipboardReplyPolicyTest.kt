package com.aichat.keyboard.reply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    @Test
    fun `the max clip age is two minutes`() {
        assertEquals(2 * 60 * 1000L, ClipboardReplyPolicy.MAX_AGE_MS)
    }

    @Test
    fun `one-time codes are never sent`() {
        listOf("123456", "1234", "12345678", "123 456", "123-456", " 4821 ").forEach {
            assertNull(ClipboardReplyPolicy.messageFrom(clip(text = it), now, fieldText = ""), it)
        }
        // Too short or too long for a code, or a normal message with a number: kept.
        listOf("123", "123456789", "hẹn 9h nhé", "2 cái").forEach {
            assertEquals(it, ClipboardReplyPolicy.messageFrom(clip(text = it), now, fieldText = ""), it)
        }
    }

    @Test
    fun `token-like strings are never sent`() {
        listOf(
            "ghp_A1b2C3d4E5f6G7h8I9j0K1l2",
            "sk-proj-abcdefghij1234567890",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123DEF456",
            "AKIAIOSFODNN7EXAMPLE",
        ).forEach {
            assertNull(ClipboardReplyPolicy.messageFrom(clip(text = it), now, fieldText = ""), it)
        }
        // A long laugh is not a token.
        assertEquals(
            "hahahahahahahahahahahaha",
            ClipboardReplyPolicy.messageFrom(clip(text = "hahahahahahahahahahahaha"), now, fieldText = ""),
        )
    }

    @Test
    fun `urls with credentials or secret parameters are never sent`() {
        listOf(
            "https://user:hunter2@example.com/x",
            "xem nè https://example.com/reset?token=abc123",
            "https://api.example.com/v1?api_key=XYZ",
            "https://example.com/login?user=a&password=b",
        ).forEach {
            assertNull(ClipboardReplyPolicy.messageFrom(clip(text = it), now, fieldText = ""), it)
        }
        assertEquals(
            "xem cái này https://example.com/a?b=1",
            ClipboardReplyPolicy.messageFrom(clip(text = "xem cái này https://example.com/a?b=1"), now, fieldText = ""),
        )
    }

    @Test
    fun `email and password pairs are never sent`() {
        listOf(
            "lan@example.com / Secret123!",
            "lan@example.com:hunter2",
            "email: lan@example.com mật khẩu: abc12345",
            "user lan@example.com password hunter2",
            "Password: hunter2",
        ).forEach {
            assertNull(ClipboardReplyPolicy.messageFrom(clip(text = it), now, fieldText = ""), it)
        }
        assertEquals(
            "gửi mình vào lan@example.com nha",
            ClipboardReplyPolicy.messageFrom(clip(text = "gửi mình vào lan@example.com nha"), now, fieldText = ""),
        )
    }

    @Test
    fun `the clipboard is a fallback only when the chat reader is off or the app is not supported`() {
        assertTrue(ClipboardReplyPolicy.mayFallBack(emptyList()))
        assertTrue(ClipboardReplyPolicy.mayFallBack(listOf(UnavailableReason.NOT_ENABLED)))
        assertTrue(ClipboardReplyPolicy.mayFallBack(listOf(UnavailableReason.UNSUPPORTED_APP)))
        assertTrue(ClipboardReplyPolicy.mayFallBack(listOf(UnavailableReason.NOT_CONNECTED)))
        // A supported messenger whose chat could not be parsed: never reply to a stale clip instead.
        assertFalse(ClipboardReplyPolicy.mayFallBack(listOf(UnavailableReason.NOTHING_FOUND)))
        assertFalse(
            ClipboardReplyPolicy.mayFallBack(listOf(UnavailableReason.NOT_ENABLED, UnavailableReason.NOTHING_FOUND)),
        )
    }
}
