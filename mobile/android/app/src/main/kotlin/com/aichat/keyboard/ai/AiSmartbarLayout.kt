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

    /**
     * Width the translate row needs besides the chip label and the translation text: chip and card padding and
     * margins, and the smartbar's edges. Generous on purpose: wrongly staying one row would clip the text.
     */
    const val TRANSLATE_ROW_CHROME_DP = 80f

    /** Extra width of the undo chip next to an applied translation, besides its label. */
    const val CHIP_CHROME_DP = 32f

    /** Whether the translate row's content ([contentWidthPx]: chip label + text + extra chips) needs two lines. */
    fun translationWraps(text: String, contentWidthPx: Float, chromePx: Float, rowWidthPx: Float): Boolean =
        '\n' in text || contentWidthPx + chromePx > rowWidthPx

    const val MIN_CARD_LINES = 2
    const val MAX_CARD_LINES = 3

    /** Rows of the smartbar's main row: grown only in the suggestions-only layout (the one that shows the AI panel). */
    /**
     * [translationWraps]: the live translation (with its language chip) does not fit on one line, measured by the
     * caller. A short translation keeps the bar one row tall instead of leaving an empty second row.
     */
    fun mainRowCount(suggestionsOnly: Boolean, state: AiAssistState, translationWraps: Boolean = true): Int = when {
        !suggestionsOnly -> 1
        state is AiAssistState.Suggestions -> SUGGESTION_ROWS
        // A summary is a few sentences plus the interest score: the tall bar, like reply suggestions.
        state is AiAssistState.Summary -> SUGGESTION_ROWS
        state is AiAssistState.Loading && state.fromSuggestions -> SUGGESTION_ROWS
        state is AiAssistState.Failed -> MESSAGE_ROWS
        // A copied message (a whole chat message) and its translation get the tall bar, like reply suggestions.
        state is AiAssistState.Translating && state.copied != null -> SUGGESTION_ROWS
        // Nothing to show yet (empty field: just the hint) or picking a language: one row, like the closed bar.
        state is AiAssistState.Translating && (state.choosingLang || state.preview == TranslatePreview.Idle) -> 1
        // Two rows only when the translation needs a second line; the caller keeps the last answer while the next
        // translation loads, so the bar does not jump while typing.
        state is AiAssistState.Translating -> if (translationWraps) MESSAGE_ROWS else 1
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
