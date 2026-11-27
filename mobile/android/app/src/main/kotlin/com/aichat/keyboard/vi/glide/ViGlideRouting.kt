/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import com.aichat.keyboard.vi.ViInputGate
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import dev.patrickgold.florisboard.ime.text.gestures.GlideLexicon

/** Pure decisions of the glide integration (`GlideTypingManager` / `TextKeyboardLayout` hooks). */
object ViGlideRouting {
    /** Smartbar candidates after the committed word. */
    const val MAX_SUGGESTIONS = 8

    /** Same window as [PreviousSyllable]; one extra char lets it see that a word was cut off. */
    const val PREVIOUS_TEXT_WINDOW = 64

    /** The Vietnamese pipeline runs when the subtype uses vi-input (see `ViInputComposer.isViInput`). */
    fun useVietnamese(isViComposer: Boolean): Boolean = isViComposer

    /** Glide is off in password fields and in the fields where composing is off ([ViInputGate]), on every subtype. */
    fun glideAllowed(isPassword: Boolean, variation: InputAttributes.Variation): Boolean =
        !isPassword && variation !in ViInputGate.BLOCKED_VARIATIONS

    /**
     * Splits [ranked] (best first) into the word to commit and up to [MAX_SUGGESTIONS] smartbar
     * candidates, all passed through [fixCase]. Nothing ranked means nothing to commit or show.
     */
    fun candidates(ranked: List<String>, fixCase: (String) -> String): Pair<String?, List<String>> {
        if (ranked.isEmpty()) return null to emptyList()
        val cased = ranked.take(MAX_SUGGESTIONS + 1).map(fixCase)
        return cased.first() to cased.drop(1)
    }

    /** The tail of the text before the cursor that [PreviousSyllable.of] needs. */
    fun previousTextWindow(textBeforeCursor: CharSequence): CharSequence {
        val start = (textBeforeCursor.length - (PREVIOUS_TEXT_WINDOW + 1)).coerceAtLeast(0)
        return textBeforeCursor.subSequence(start, textBeforeCursor.length)
    }
}

/**
 * Classifier word source on the Vietnamese subtype: the diacritic-free folded forms, weighted by their
 * summed counts. Equal by the wrapped [lexicon] instance, so a reloaded lexicon rebuilds the pruner.
 */
class ViGlideClassifierLexicon(val lexicon: ViGlideLexicon) : GlideLexicon {
    override val words: List<String> get() = lexicon.foldedForms

    override fun frequency(word: String): Double = lexicon.foldedFrequency(word)

    override fun equals(other: Any?): Boolean = other is ViGlideClassifierLexicon && other.lexicon === lexicon

    override fun hashCode(): Int = System.identityHashCode(lexicon)
}
