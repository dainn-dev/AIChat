/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import java.text.Normalizer
import java.util.Locale

/** Extracts the word right before the cursor, used as bigram context for glide ranking. */
object PreviousSyllable {
    /** Only the tail of the text matters; no syllable or English word is longer than this. */
    private const val WINDOW = 64

    /** Characters that may directly precede the token: whitespace separators and opening punctuation. */
    private const val BOUNDARIES = " \n\r([\"“'‘"

    /**
     * The lowercased NFC token before the cursor when it is separated from the cursor by spaces
     * alone, stands alone (preceded by the text start, a space, a newline or an opening quote/bracket) and satisfies [isKnown].
     * Null mid-word, after punctuation, a newline, a digit or an emoji, and at the start.
     */
    fun of(textBeforeCursor: CharSequence, isKnown: (String) -> Boolean): String? {
        val truncated = textBeforeCursor.length > WINDOW
        val text = Normalizer.normalize(textBeforeCursor.takeLast(WINDOW), Normalizer.Form.NFC)

        val end = text.trimEnd(' ').length
        if (end == text.length || end == 0) return null // mid-word, empty or only spaces

        var start = end
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start == end) return null // does not end in a letter
        if (start == 0 && truncated) return null
        if (start > 0 && text[start - 1] !in BOUNDARIES) return null

        val token = text.substring(start, end).lowercase(Locale.ROOT)
        return token.takeIf(isKnown)
    }
}
