/*
 * Ported from Bamboo (https://github.com/BambooEngine/bamboo-core, utils.go),
 * commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
 * Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
 */
package com.aichat.vi

/** Vietnamese tone marks. Ordinals match Bamboo's `Tone` values and the column order of [ViChars.VOWELS]. */
internal enum class Tone { NONE, GRAVE, ACUTE, HOOK, TILDE, DOT }

/** Letter modifiers. Ordinals index the strings of [ViChars] mark families. */
internal enum class Mark { NONE, HAT, BREVE, HORN, DASH, RAW }

/** Character-level helpers for Vietnamese letters (tones, marks, classification). */
internal object ViChars {
    /** Every lowercase vowel, 6 tone variants per base vowel in [Tone] order. */
    const val VOWELS: String =
        "aàáảãạăằắẳẵặâầấẩẫậeèéẻẽẹêềếểễệiìíỉĩịoòóỏõọôồốổỗộơờớởỡợuùúủũụưừứửữựyỳýỷỹỵ"

    private const val TONES_PER_VOWEL = 6
    private const val NO_MARK = '_'

    private val PUNCTUATION: Set<Char> = setOf(
        ',', ';', ':', '.', '"', '\'', '!', '?', ' ',
        '<', '>', '=', '+', '-', '*', '/', '\\',
        '_', '~', '`', '@', '#', '$', '%', '^', '&', '(', ')', '{', '}', '[', ']', '|',
    )

    /** Mark family strings indexed by [Mark] ordinal ('_' = mark not applicable). */
    private val MARK_FAMILIES: Map<Char, String> = buildMap {
        "aâă".forEach { put(it, "aâă__") }
        "eê".forEach { put(it, "eê___") }
        "oôơ".forEach { put(it, "oô_ơ_") }
        "uư".forEach { put(it, "u__ư_") }
        "dđ".forEach { put(it, "d___đ") }
    }

    fun isVowel(c: Char): Boolean = VOWELS.indexOf(c) >= 0

    fun isAsciiAlpha(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    fun isPunctuation(c: Char): Boolean = c in PUNCTUATION

    fun isWordBreakSymbol(c: Char): Boolean = isPunctuation(c) || c in '0'..'9'

    fun toneOf(c: Char): Tone {
        val pos = VOWELS.indexOf(c)
        return if (pos < 0) Tone.NONE else Tone.entries[pos % TONES_PER_VOWEL]
    }

    fun withTone(c: Char, tone: Tone): Char {
        val pos = VOWELS.indexOf(c)
        if (pos < 0) return c
        return VOWELS[pos - pos % TONES_PER_VOWEL + tone.ordinal]
    }

    /** All members of [c]'s mark family (e.g. 'o' -> o, ô, ơ), or empty if it has none. */
    fun markFamily(c: Char): List<Char> = MARK_FAMILIES[c].orEmpty().filter { it != NO_MARK }.toList()

    /** Position of [c] in its own mark family, i.e. the mark it currently carries. */
    fun markOf(c: Char): Mark? {
        val family = MARK_FAMILIES[c] ?: return null
        return Mark.entries[family.indexOf(c)]
    }

    /** Applies [mark] to a toneless char; returns [c] unchanged when the mark does not apply. */
    fun withMarkToneless(c: Char, mark: Mark): Char {
        val family = MARK_FAMILIES[c] ?: return c
        if (mark.ordinal >= family.length) return c
        val marked = family[mark.ordinal]
        return if (marked == NO_MARK) c else marked
    }

    /** Applies [mark] while preserving the tone already on [c]. */
    fun withMark(c: Char, mark: Mark): Char {
        val tone = toneOf(c)
        return withTone(withMarkToneless(withTone(c, Tone.NONE), mark), tone)
    }

    /** Strips tone and mark: 'ộ' -> 'o', 'đ' -> 'd'. */
    fun base(c: Char): Char = withMarkToneless(withTone(c, Tone.NONE), Mark.NONE)

    /** True when [c] (lowercase) carries a tone or a mark, i.e. it is not plain ASCII. */
    fun isVietnameseRune(c: Char): Boolean =
        toneOf(c) != Tone.NONE || c != withMarkToneless(c, Mark.NONE)

    fun hasAnyVietnameseRune(text: CharSequence): Boolean = text.any { isVietnameseRune(it.lowercaseChar()) }

    fun hasAnyVowel(text: CharSequence): Boolean = text.any { isVowel(it.lowercaseChar()) }
}
