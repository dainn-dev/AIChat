/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import dev.patrickgold.florisboard.ime.editor.InputAttributes.Type
import dev.patrickgold.florisboard.ime.editor.InputAttributes.Variation
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.keyboard.extCoreComposer
import dev.patrickgold.florisboard.ime.text.composing.Appender
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViInputGateTest {
    private fun eligible(
        type: Type = Type.TEXT,
        variation: Variation = Variation.NORMAL,
        mode: KeyboardMode = KeyboardMode.CHARACTERS,
        vi: Boolean = true,
    ) = ViInputGate.isEligible(vi, mode, type, variation)

    @Test
    fun `plain text fields compose`() {
        assertTrue(eligible())
        assertTrue(eligible(variation = Variation.SHORT_MESSAGE))
        assertTrue(eligible(variation = Variation.WEB_EDIT_TEXT))
    }

    @Test
    fun `all password input types are plain input`() {
        assertFalse(eligible(variation = Variation.PASSWORD))
        assertFalse(eligible(variation = Variation.VISIBLE_PASSWORD))
        assertFalse(eligible(variation = Variation.WEB_PASSWORD))
        // TYPE_NUMBER_VARIATION_PASSWORD: a number field, never composed.
        assertFalse(eligible(type = Type.NUMBER, variation = Variation.PASSWORD))
    }

    @Test
    fun `email and URL fields are plain input`() {
        assertFalse(eligible(variation = Variation.EMAIL_ADDRESS))
        assertFalse(eligible(variation = Variation.WEB_EMAIL_ADDRESS))
        assertFalse(eligible(variation = Variation.URI))
    }

    @Test
    fun `symbols and numeric layers are plain input`() {
        assertFalse(eligible(mode = KeyboardMode.SYMBOLS))
        assertFalse(eligible(mode = KeyboardMode.SYMBOLS2))
        assertFalse(eligible(mode = KeyboardMode.NUMERIC_ADVANCED))
    }

    @Test
    fun `other subtypes are plain input`() {
        assertFalse(eligible(vi = false))
    }

    @Test
    fun `legacy telex subtypes use vi-input`() {
        assertTrue(ViInputComposer.isViInput(extCoreComposer("telex"), null))
        assertTrue(ViInputComposer.isViInput(extCoreComposer("vi-input"), ViInputComposer))
        assertFalse(ViInputComposer.isViInput(extCoreComposer("appender"), Appender))
    }
}
