package com.aichat.keyboard.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingConsentRequestTest {
    private var now = 1_000_000L
    private fun request() = PendingConsentRequest(clock = { now })

    @Test
    fun `a request is set only when settings could be launched`() {
        val r = request()
        assertFalse(r.open(launch = { false }))
        assertNull(r.pending.value)
        assertFalse(r.consume())

        assertTrue(r.open(launch = { true }))
        assertEquals(now, r.pending.value)
    }

    @Test
    fun `a fresh request is consumed once`() {
        val r = request()
        r.open(launch = { true })
        now += 2_000L
        assertTrue(r.consume())
        assertFalse(r.consume())
        assertNull(r.pending.value)
    }

    @Test
    fun `a stale request never navigates a later launch`() {
        val r = request()
        r.open(launch = { true })
        now += PendingConsentRequest.TTL_MS + 1
        assertFalse(r.consume())
        assertNull(r.pending.value)
    }
}
