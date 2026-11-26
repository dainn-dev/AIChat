package com.aichat.keyboard.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecognizerTrustTest {
    private val google = RecognizerCandidate("com.google.android.googlequicksearchbox", isSystemApp = true)
    private val samsung = RecognizerCandidate("com.samsung.android.bixby.agent", isSystemApp = true)
    private val thirdParty = RecognizerCandidate("com.example.speech", isSystemApp = false)

    @Test
    fun `a third-party recognizer screen is never used`() {
        assertNull(RecognizerTrust.pickTrusted(listOf(thirdParty), defaultServicePackage = null))
        assertNull(RecognizerTrust.pickTrusted(listOf(thirdParty), defaultServicePackage = "com.other"))
    }

    @Test
    fun `the default recognition service's own screen is preferred`() {
        assertEquals(
            samsung.packageName,
            RecognizerTrust.pickTrusted(listOf(google, thirdParty, samsung), defaultServicePackage = samsung.packageName),
        )
        // The user chose this recognition service in the system settings: its screen is trusted too.
        assertEquals(
            thirdParty.packageName,
            RecognizerTrust.pickTrusted(listOf(google, thirdParty), defaultServicePackage = thirdParty.packageName),
        )
    }

    @Test
    fun `otherwise the first system app is used`() {
        assertEquals(
            google.packageName,
            RecognizerTrust.pickTrusted(listOf(thirdParty, google, samsung), defaultServicePackage = null),
        )
        assertEquals(
            google.packageName,
            RecognizerTrust.pickTrusted(listOf(thirdParty, google), defaultServicePackage = "com.not.installed"),
        )
    }

    @Test
    fun `no candidates means no trusted screen`() {
        assertNull(RecognizerTrust.pickTrusted(emptyList(), defaultServicePackage = "com.google.android.tts"))
    }

    @Test
    fun `the default service package comes from its flattened component name`() {
        assertEquals(
            "com.google.android.tts",
            RecognizerTrust.packageOfComponent("com.google.android.tts/com.google.android.apps.speech.tts.Service"),
        )
        assertEquals("com.example", RecognizerTrust.packageOfComponent("com.example/.Svc"))
        assertNull(RecognizerTrust.packageOfComponent(null))
        assertNull(RecognizerTrust.packageOfComponent(""))
        assertNull(RecognizerTrust.packageOfComponent("/x"))
        assertNull(RecognizerTrust.packageOfComponent("nopackage"))
    }
}
