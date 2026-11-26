package com.aichat.keyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingVoiceTextTest {
    @Test
    fun `recognized text goes back to the app it was spoken for, once`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", targetPackage = "com.zing.zalo", nowMs = 1_000L)
        assertEquals("hello", pending.consume(packageName = "com.zing.zalo", nowMs = 2_000L))
        assertNull(pending.consume(packageName = "com.zing.zalo", nowMs = 2_001L))
    }

    @Test
    fun `text is never committed into another app`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", targetPackage = "com.zing.zalo", nowMs = 1_000L)
        assertNull(pending.consume(packageName = "com.facebook.orca", nowMs = 2_000L))
        // Still waiting for the right app.
        assertEquals("hello", pending.consume(packageName = "com.zing.zalo", nowMs = 3_000L))
    }

    @Test
    fun `stale text expires`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", targetPackage = "com.zing.zalo", nowMs = 1_000L)
        assertNull(pending.consume(packageName = "com.zing.zalo", nowMs = 1_000L + PendingVoiceText.TTL_MS + 1))
    }

    @Test
    fun `an unknown target package or blank text is not kept`() {
        val pending = PendingVoiceText()
        pending.offer(text = "hello", targetPackage = null, nowMs = 1_000L)
        assertNull(pending.consume(packageName = null, nowMs = 1_000L))
        pending.offer(text = "  ", targetPackage = "a", nowMs = 1_000L)
        assertNull(pending.consume(packageName = "a", nowMs = 1_000L))
    }
}
