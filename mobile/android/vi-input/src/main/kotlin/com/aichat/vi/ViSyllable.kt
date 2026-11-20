/*
 * Ported from Bamboo (https://github.com/BambooEngine/bamboo-core, spelling.go and the CVC
 * splitting in bamboo_utils.go), commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
 * Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
 */
package com.aichat.vi

/** A syllable split into initial consonant, vowel nucleus and final consonant. */
public data class SyllableParts(val initial: String, val vowel: String, val final: String)

/**
 * Vietnamese spelling validator: checks that a single syllable is a legal combination of
 * initial consonant + vowel + final consonant, with a tone compatible with the final
 * (`-c -ch -p -t` only take sắc/nặng).
 */
public object ViSyllable {
    private val FIRST_CONSONANTS: List<List<String>> = rows(
        "b d đ g gh m n nh p ph r s t tr v z",
        "c h k kh qu th",
        "ch gi l ng ngh x",
        "đ l",
        "h",
    )

    private val VOWELS: List<List<String>> = rows(
        "ê i ua uê uy y",
        "a iê oa uyê yê",
        "â ă e o oo ô ơ oe u ư uâ uô ươ",
        "oă",
        "uơ",
        "ai ao au âu ay ây eo êu ia iêu iu oai oao oay oeo oi ôi ơi ưa uây ui ưi uôi ươi ươu ưu uya uyu yêu",
        "ă",
        "i",
    )

    private val LAST_CONSONANTS: List<List<String>> = rows("ch nh", "c ng", "m n p t", "k", "c")

    /** Which vowel rows each first-consonant row may combine with. */
    private val CV_MATRIX: List<Set<Int>> = listOf(
        setOf(0, 1, 2, 5), setOf(0, 1, 2, 3, 4, 5), setOf(0, 1, 2, 3, 5), setOf(6), setOf(7),
    )

    /** Which last-consonant rows each vowel row may combine with. */
    private val VC_MATRIX: List<Set<Int>> = listOf(
        setOf(0, 2), setOf(0, 1, 2), setOf(1, 2), setOf(1, 2), emptySet(), emptySet(), setOf(3), setOf(4),
    )

    private val STOP_FINALS: Set<String> = setOf("c", "k", "p", "t", "ch")

    private fun rows(vararg lines: String): List<List<String>> = lines.map { it.split(' ') }

    /**
     * True if [word] is a valid Vietnamese syllable. With [complete] = false, a syllable that is
     * still being typed is accepted when it is a prefix of a valid one (`ngh`, `nguo`).
     */
    public fun isValid(word: String, complete: Boolean = true): Boolean {
        if (word.isEmpty()) return false
        val lower = word.lowercase()
        val tones = lower.map(ViChars::toneOf).filter { it != Tone.NONE }
        if (tones.size > 1) return false
        val parts = split(lower)
        if (tones.isNotEmpty() && !toneFitsFinal(tones.single(), parts.final)) return false
        val strip: (String) -> String = { s -> s.map { ViChars.withTone(it, Tone.NONE) }.joinToString("") }
        return isValidCvc(strip(parts.initial), strip(parts.vowel), parts.final, complete)
    }

    /** Splits [word] into initial/vowel/final, treating `gi` and `qu` as initials (`gia`, `qua`). */
    public fun split(word: String): SyllableParts {
        val chars = word.toList()
        val (fc, vo, lc) = splitCvc(chars, { ViChars.isVowel(it.lowercaseChar()) }) { ViChars.base(it.lowercaseChar()) }
        return SyllableParts(fc.joinToString(""), vo.joinToString(""), lc.joinToString(""))
    }

    internal fun toneFitsFinal(tone: Tone, lastConsonants: String): Boolean =
        tone == Tone.NONE || tone == Tone.ACUTE || tone == Tone.DOT || lastConsonants !in STOP_FINALS

    /** Port of Bamboo's isValidCVC. Inputs must be lowercase and toneless. */
    internal fun isValidCvc(fc: String, vo: String, lc: String, complete: Boolean): Boolean {
        val fcRows = if (fc.isEmpty()) emptyList() else lookup(FIRST_CONSONANTS, fc, complete || vo.isNotEmpty(), true)
        if (fc.isNotEmpty() && fcRows.isEmpty()) return false
        val voRows = if (vo.isEmpty()) emptyList() else lookup(VOWELS, vo, complete || lc.isNotEmpty(), complete)
        if (vo.isNotEmpty() && voRows.isEmpty()) return false
        val lcRows = if (lc.isEmpty()) emptyList() else lookup(LAST_CONSONANTS, lc, complete, true)
        if (lc.isNotEmpty() && lcRows.isEmpty()) return false

        if (voRows.isEmpty()) return fcRows.isNotEmpty()
        if (fcRows.isNotEmpty() && !combines(fcRows, voRows, CV_MATRIX)) return false
        return lcRows.isEmpty() || combines(voRows, lcRows, VC_MATRIX)
    }

    private fun combines(left: List<Int>, right: List<Int>, matrix: List<Set<Int>>): Boolean =
        left.any { l -> right.any { r -> r in matrix[l] } }

    /**
     * Indexes of the rows containing [input]. [full]: lengths must match (otherwise [input] may be
     * a prefix). [complete]: chars must match exactly (otherwise `o` also matches `ô`/`ơ`).
     */
    private fun lookup(table: List<List<String>>, input: String, full: Boolean, complete: Boolean): List<Int> =
        table.indices.filter { row ->
            table[row].any { candidate ->
                candidate.length >= input.length &&
                    !(full && candidate.length > input.length) &&
                    input.indices.all { k ->
                        input[k] == candidate[k] ||
                            (!complete && ViChars.withMarkToneless(candidate[k], Mark.NONE) == input[k])
                    }
            }
        }
}

/**
 * Splits a sequence of letters into (initial consonants, vowels, final consonants), port of
 * Bamboo's extractCvcAppendingTrans. [lowerBase] gives the comparable char for the gi/qu rule.
 */
internal fun <T> splitCvc(
    letters: List<T>,
    isVowel: (T) -> Boolean,
    lowerBase: (T) -> Char,
): Triple<List<T>, List<T>, List<T>> {
    val lastStart = trailingRunStart(letters, letters.size, vowels = false, isVowel)
    val vowelStart = trailingRunStart(letters, lastStart, vowels = true, isVowel)
    var fc = letters.subList(0, vowelStart)
    var vo = letters.subList(vowelStart, lastStart)
    var lc = letters.subList(lastStart, letters.size)
    if (lc.isNotEmpty() && vo.isEmpty() && fc.isEmpty()) {
        fc = lc
        lc = emptyList()
    }
    // 'gi' and 'qu' count as initials: [g, ia, ''] -> [gi, a, ''], [q, ua, ''] -> [qu, a, ''],
    // except [g, ie, ng] which stays as is.
    if (fc.size == 1 && vo.isNotEmpty()) {
        val first = lowerBase(fc[0])
        val v0 = lowerBase(vo[0])
        val gi = first == 'g' && v0 == 'i' && vo.size > 1 && !(lowerBase(vo[1]) == 'e' && lc.isNotEmpty())
        val qu = first == 'q' && v0 == 'u'
        if (gi || qu) {
            fc = fc + vo[0]
            vo = vo.subList(1, vo.size)
        }
    }
    return Triple(fc, vo, lc)
}

/** Start index of the run, ending before [end], of letters that are vowels (or consonants). */
private fun <T> trailingRunStart(letters: List<T>, end: Int, vowels: Boolean, isVowel: (T) -> Boolean): Int {
    var i = end
    while (i > 0 && isVowel(letters[i - 1]) == vowels) i--
    return i
}
