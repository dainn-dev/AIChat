package com.aichat.keyboard.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureSupportTest {
    private val pkg = "online.dainn.aichat.keyboard.debug"
    private val cls = "com.aichat.keyboard.capture.ChatCaptureService"

    @Test
    fun `supported messengers map to their backend platform ids`() {
        assertEquals(SupportedMessenger.MESSENGER, SupportedMessenger.fromPackage("com.facebook.orca"))
        assertEquals(SupportedMessenger.ZALO, SupportedMessenger.fromPackage("com.zing.zalo"))
        assertEquals("messenger", SupportedMessenger.MESSENGER.platform)
        assertEquals("zalo", SupportedMessenger.ZALO.platform)
        assertNull(SupportedMessenger.fromPackage("com.facebook.katana"))
        assertNull(SupportedMessenger.fromPackage(null))
    }

    @Test
    fun `platform ids match the backend pattern`() {
        val pattern = Regex("[a-z0-9._-]{1,64}")
        SupportedMessenger.entries.forEach { assertTrue(pattern.matches(it.platform), it.platform) }
    }

    @Test
    fun `the service is enabled when its component is in the secure setting`() {
        assertTrue(CaptureServiceState.isComponentEnabled("$pkg/$cls", pkg, cls))
        assertTrue(CaptureServiceState.isComponentEnabled("other.app/.Svc:$pkg/$cls", pkg, cls))
        assertTrue(CaptureServiceState.isComponentEnabled(" $pkg/$cls ", pkg, cls))
    }

    @Test
    fun `the short class form relative to the package is understood`() {
        val shortPkg = "com.aichat.keyboard"
        assertTrue(CaptureServiceState.isComponentEnabled("$shortPkg/.capture.ChatCaptureService", shortPkg, cls))
    }

    @Test
    fun `other components, other packages and empty settings are not enabled`() {
        assertFalse(CaptureServiceState.isComponentEnabled(null, pkg, cls))
        assertFalse(CaptureServiceState.isComponentEnabled("", pkg, cls))
        assertFalse(CaptureServiceState.isComponentEnabled("other.app/.Svc", pkg, cls))
        assertFalse(CaptureServiceState.isComponentEnabled("online.dainn.aichat.keyboard/$cls", pkg, cls))
        assertFalse(CaptureServiceState.isComponentEnabled("garbage", pkg, cls))
    }
}
