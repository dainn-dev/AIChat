package com.aichat.keyboard.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `WhatsApp, Gmail and Google Chat are supported, WhatsApp Business shares the WhatsApp platform`() {
        assertEquals("whatsapp", SupportedMessenger.fromPackage("com.whatsapp")?.platform)
        assertEquals("whatsapp", SupportedMessenger.fromPackage("com.whatsapp.w4b")?.platform)
        assertEquals("gmail", SupportedMessenger.fromPackage("com.google.android.gm")?.platform)
        assertEquals("google_chat", SupportedMessenger.fromPackage("com.google.android.apps.dynamite")?.platform)
    }

    @Test
    fun `the accessibility service is not limited to a package list`() {
        // Gradle runs unit tests from the module directory.
        val xml = java.io.File("src/main/res/xml/aichat_accessibility_service.xml").readText()

        assertFalse("android:packageNames" in xml)
    }

    @Test
    fun `platform ids match the backend pattern`() {
        val pattern = Regex("[a-z0-9._-]{1,64}")
        SupportedMessenger.entries.forEach { assertTrue(pattern.matches(it.platform), it.platform) }
        listOf("org.telegram.messenger", "jp.naver.line.android", "Com.Example_App", "a".repeat(200)).forEach {
            val platform = assertNotNull(CapturePlatform.of(it))
            assertTrue(pattern.matches(platform), platform)
        }
    }

    @Test
    fun `known messengers keep their platform id, other apps use the package name`() {
        assertEquals("zalo", CapturePlatform.of("com.zing.zalo"))
        assertEquals("whatsapp", CapturePlatform.of("com.whatsapp.w4b"))
        assertEquals("org.telegram.messenger", CapturePlatform.of("org.telegram.messenger"))
        assertEquals("com.example_app", CapturePlatform.of("Com.Example_App"))
        assertNull(CapturePlatform.of(null))
        assertNull(CapturePlatform.of(""))
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
