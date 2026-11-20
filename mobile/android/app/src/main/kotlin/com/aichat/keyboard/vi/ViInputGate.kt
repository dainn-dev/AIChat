/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode

/** Decides whether keys go through the Vietnamese engine. Pure, so it is unit-tested. */
object ViInputGate {
    /** Text variations where composing would corrupt the input. */
    val BLOCKED_VARIATIONS: Set<InputAttributes.Variation> = setOf(
        InputAttributes.Variation.PASSWORD,
        InputAttributes.Variation.VISIBLE_PASSWORD,
        InputAttributes.Variation.WEB_PASSWORD,
        // Addresses are ASCII; Telex would turn e.g. "dd" or "ow" into Vietnamese letters.
        InputAttributes.Variation.EMAIL_ADDRESS,
        InputAttributes.Variation.WEB_EMAIL_ADDRESS,
        InputAttributes.Variation.URI,
    )

    /**
     * @param isViComposer the active subtype uses vi-input (see [ViInputComposer.isViInput]).
     * @param keyboardMode letters layer only: VNI tone digits must not fire from the symbols layer.
     * @param type / [variation] the field's input type. Only text fields compose; numeric fields
     *   (including number passwords) never do.
     */
    fun isEligible(
        isViComposer: Boolean,
        keyboardMode: KeyboardMode,
        type: InputAttributes.Type,
        variation: InputAttributes.Variation,
    ): Boolean = isViComposer &&
        keyboardMode == KeyboardMode.CHARACTERS &&
        type == InputAttributes.Type.TEXT &&
        variation !in BLOCKED_VARIATIONS
}
