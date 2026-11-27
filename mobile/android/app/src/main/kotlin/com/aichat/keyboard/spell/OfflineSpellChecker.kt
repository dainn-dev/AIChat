/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.spell

import com.aichat.keyboard.vi.glide.ViGlideLexicon
import com.aichat.vi.ViText

/** A word list the checker can ask: is this word known, and what could it have been. */
interface SpellDictionary {
    fun contains(word: String): Boolean

    /** Likely intended words for the unknown [word], best first. Empty when there is no confident guess. */
    fun suggestions(word: String, max: Int): List<String>
}

/** The result of checking one word. */
sealed interface SpellVerdict {
    /** Known, not checkable (number, link, acronym...) or no confident correction: never underlined. */
    data object Valid : SpellVerdict

    /** Unknown word with at least one confident correction. */
    data class Typo(val suggestions: List<String>) : SpellVerdict
}

/**
 * Offline spell check of single words. A word is only flagged when the dictionary can name a correction,
 * so names, slang and brand words that are merely missing from a list are left alone instead of underlined.
 * Pure Kotlin; nothing leaves the device.
 */
object OfflineSpellChecker {
    private const val MIN_LENGTH = 2

    fun check(word: String, dictionary: SpellDictionary, maxSuggestions: Int = 5): SpellVerdict {
        if (!isCheckable(word) || dictionary.contains(word)) return SpellVerdict.Valid
        val suggestions = dictionary.suggestions(word, maxSuggestions)
            .filter { !it.equals(word, ignoreCase = true) }
            .map { matchCase(word, it) }
            .distinct()
        return if (suggestions.isEmpty()) SpellVerdict.Valid else SpellVerdict.Typo(suggestions)
    }

    /** Letters only: skips numbers, links, e-mail addresses, hashtags, single letters and ALL-CAPS acronyms. */
    internal fun isCheckable(word: String): Boolean {
        if (word.length < MIN_LENGTH) return false
        if (!word.all { it.isLetter() || it == '\'' || it == '’' || it == '-' }) return false
        val letters = word.filter { it.isLetter() }
        return !(letters.length > 1 && letters.all { it.isUpperCase() })
    }

    /** `Ngay` -> `Ngày`, `ngay` -> `ngày`. (ALL-CAPS words are acronyms and never reach here.) */
    internal fun matchCase(original: String, suggestion: String): String =
        if (original.first().isUpperCase()) suggestion.replaceFirstChar { it.uppercase() } else suggestion
}

/**
 * English words with frequencies. Suggestions are the known words one edit away (a letter dropped, added,
 * changed or two swapped), most frequent first.
 */
class EnglishSpellDictionary(private val words: Map<String, Int>) : SpellDictionary {
    override fun contains(word: String): Boolean = normalize(word) in words

    override fun suggestions(word: String, max: Int): List<String> {
        val w = normalize(word)
        return editsOne(w)
            .filter { it != w && it in words }
            .distinct()
            .sortedByDescending { words.getValue(it) }
            .take(max)
            .toList()
    }

    private fun normalize(word: String) = word.lowercase().replace('’', '\'')

    private fun editsOne(w: String): Sequence<String> = sequence {
        for (i in 0..w.length) {
            if (i < w.length) yield(w.removeRange(i, i + 1))
            if (i < w.length - 1) yield(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2))
            for (c in ALPHABET) {
                if (i < w.length) yield(w.substring(0, i) + c + w.substring(i + 1))
                yield(w.substring(0, i) + c + w.substring(i))
            }
        }
    }

    private companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
    }
}

/**
 * Vietnamese syllables. The usual Vietnamese typo is a missing or wrong tone mark (`toi`, `ngay`, `dc`-style
 * typing without marks), so an unknown syllable is matched on its mark-free form and offered with every
 * accented spelling that exists, most frequent first.
 */
class ViSpellDictionary(private val lexicon: ViGlideLexicon) : SpellDictionary {
    override fun contains(word: String): Boolean = lexicon.isKnown(word)

    override fun suggestions(word: String, max: Int): List<String> =
        lexicon.expansions(ViText.fold(word))
            .filter { !it.english }
            .map { it.word }
            .take(max)
}
