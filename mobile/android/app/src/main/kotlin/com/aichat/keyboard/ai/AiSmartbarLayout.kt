/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import kotlin.math.floor

/**
 * Size rules of the AI panel in the smartbar. Pure Kotlin, unit-tested.
 *
 * Reply suggestions are whole sentences, so while they are shown the smartbar grows to [SUGGESTION_ROWS] rows:
 * one row of chips (back + tones) and the rest for multi-line suggestion cards. It stays that tall while replies in
 * another tone load, so the bar does not jump. Failure messages get [MESSAGE_ROWS] rows so they can wrap instead of
 * being cut. Every other AI state keeps one row.
 */
object AiSmartbarLayout {
    const val SUGGESTION_ROWS = 3

    /** Rows while a failure message is shown: room for two lines of text. */
    const val MESSAGE_ROWS = 2

    /** Lines a failure message may wrap to. */
    const val MESSAGE_MAX_LINES = 2

    /** Text size of the AI chips and cards; the smartbar's own size (~17sp) makes chips overflow. */
    const val TEXT_SIZE_SP = 15f
    const val LINE_HEIGHT_SP = 19f

    /** Card padding (8dp + 8dp) and top margin (4dp) from the chip style, in dp. */
    const val CARD_VERTICAL_CHROME_DP = 20f

    const val MIN_CARD_LINES = 2
    const val MAX_CARD_LINES = 3

    /** Rows of the smartbar's main row: grown only in the suggestions-only layout (the one that shows the AI panel). */
    fun mainRowCount(suggestionsOnly: Boolean, state: AiAssistState): Int = when {
        !suggestionsOnly -> 1
        state is AiAssistState.Suggestions -> SUGGESTION_ROWS
        state is AiAssistState.Loading && state.fromSuggestions -> SUGGESTION_ROWS
        state is AiAssistState.Failed -> MESSAGE_ROWS
        // A copied message (a whole chat message) and its translation get the tall bar, like reply suggestions.
        state is AiAssistState.Translating && state.copied != null -> SUGGESTION_ROWS
        // The translation may wrap; always two rows so the bar keeps its height while the preview updates.
        state is AiAssistState.Translating -> MESSAGE_ROWS
        else -> 1
    }

    /**
     * How many text lines fit in a suggestion card of [cardAreaHeightDp], at 1sp = 1dp. Callers divide the height
     * by the font scale first, so a larger system font gets fewer lines instead of clipped ones.
     */
    fun cardMaxLines(cardAreaHeightDp: Float): Int =
        floor((cardAreaHeightDp - CARD_VERTICAL_CHROME_DP) / LINE_HEIGHT_SP).toInt()
            .coerceIn(MIN_CARD_LINES, MAX_CARD_LINES)
}
