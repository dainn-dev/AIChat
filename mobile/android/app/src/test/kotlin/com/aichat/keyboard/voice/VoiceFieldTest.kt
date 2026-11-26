package com.aichat.keyboard.voice

import android.text.InputType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceFieldTest {
    private fun field(inputType: Int) = VoiceField("com.example", 1, null, inputType, 0)

    @Test
    fun `password variations and raw editors are private`() {
        val private = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            InputType.TYPE_NULL,
        )
        for (type in private) assertTrue(field(type).isPrivate, "inputType=$type")
    }

    @Test
    fun `ordinary fields are not private`() {
        val ordinary = listOf(
            InputType.TYPE_CLASS_TEXT,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_NUMBER,
        )
        for (type in ordinary) assertFalse(field(type).isPrivate, "inputType=$type")
    }

    @Test
    fun `voice is refused in private fields, password keyboards and incognito mode`() {
        val chat = field(InputType.TYPE_CLASS_TEXT)
        assertFalse(VoiceInputRouting.isBlocked(chat, keyboardPassword = false, incognito = false))
        assertTrue(VoiceInputRouting.isBlocked(chat, keyboardPassword = true, incognito = false))
        assertTrue(VoiceInputRouting.isBlocked(chat, keyboardPassword = false, incognito = true))
        assertTrue(
            VoiceInputRouting.isBlocked(
                field(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
                keyboardPassword = false,
                incognito = false,
            ),
        )
        // No editor bound at all.
        assertTrue(VoiceInputRouting.isBlocked(null, keyboardPassword = false, incognito = false))
    }
}
