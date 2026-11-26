/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.suggest

import com.aichat.keyboard.sticker.StickerTag
import java.text.Normalizer
import java.util.Locale

/**
 * Maps the words just before the cursor (the last 1-3 words, plus up to two trailing particles such as "nha", "nhé",
 * "rồi") onto [StickerTag]s, fully offline. Pure Kotlin, unit-tested.
 *
 * Matching is whole-word only: words are split on whitespace, so "kok" never matches "ok" and "thanksgiving" never
 * matches "thanks". It is case-insensitive and tolerant of missing Vietnamese tone marks: a word typed without any
 * marks ("cam on") matches the keyword "cảm ơn", but a word typed with different marks ("đổi") does not match
 * a keyword that only differs by marks ("đói"). Keywords whose unmarked form is a common other word ("bận" / "bạn",
 * "nhớ" / "nhỏ", "chờ" / "cho") are [Keyword.accentRequired]: they only match when typed with their marks.
 */
object StickerKeywordMatcher {
    /** The tags to suggest and the exact text that triggered them (a suffix of the text before the cursor). */
    data class Match(
        val tags: List<StickerTag>,
        /**
         * From the first keyword letter up to the cursor, including trailing particles, punctuation and spaces. It is
         * what a sent sticker replaces (see [StickerSuggestLogic.charsToDelete]).
         */
        val trigger: String,
    )

    /** Characters read before the cursor; enough for 3 words and 2 particles. */
    const val WINDOW = 64

    private const val MAX_WORDS = 3
    private const val MAX_PARTICLES = 2

    /** Allowed between the keyword and the cursor besides spaces. */
    private const val TRAILING_PUNCTUATION = ".!?,~…"

    /** At most this much trailing whitespace/punctuation; more means the user has moved on. */
    private const val MAX_TRAILING = 6

    /**
     * Tags for the words right before the cursor, or null. [textAfterCursor] guards against a cursor inside a
     * word ("ok|ay"). [textBeforeCursor] may be longer than [WINDOW]; only its tail is looked at.
     */
    fun match(textBeforeCursor: String, textAfterCursor: String = ""): Match? {
        if (textAfterCursor.firstOrNull()?.let(::isWordChar) == true) return null
        val truncated = textBeforeCursor.length > WINDOW
        val text = if (truncated) textBeforeCursor.takeLast(WINDOW) else textBeforeCursor

        var end = text.length
        while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] in TRAILING_PUNCTUATION)) {
            if (text[end - 1] == '\n') return null
            end--
        }
        if (end == 0 || text.length - end > MAX_TRAILING) return null

        val tokens = tokensBefore(text, end, truncated)
        if (tokens.isEmpty()) return null

        for (particles in 0..MAX_PARTICLES) {
            if (particles >= tokens.size) break
            val core = tokens.subList(0, tokens.size - particles)
            if (particles > 0 && !tokens.subList(core.size, tokens.size).all { isParticle(it.word) }) break
            for (length in minOf(MAX_WORDS, core.size) downTo 1) {
                val phrase = core.subList(core.size - length, core.size)
                val tags = tagsFor(phrase.map { it.word }) ?: continue
                return Match(tags = tags, trigger = text.substring(phrase.first().wordStart))
            }
        }
        return null
    }

    // -- Tokens --------------------------------------------------------------------------------------------------

    /**
     * A word: a run of letters/digits, or a whole emoticon. [word] is its normalized form, [wordStart] its index in
     * the text. An empty [word] stands for punctuation that never matches ("--").
     */
    private class Token(val word: String, val wordStart: Int)

    /**
     * Up to [MAX_WORDS] + [MAX_PARTICLES] words ending at [end], oldest first; stops at a line break. Whitespace and
     * punctuation separate words ("nhé,cảm ơn" has three), except inside an emoticon (":)))", "=))").
     */
    private fun tokensBefore(text: String, end: Int, truncated: Boolean): List<Token> {
        val tokens = ArrayDeque<Token>()
        var i = end
        while (tokens.size < MAX_WORDS + MAX_PARTICLES && i > 0) {
            val chunkEnd = i
            while (i > 0 && !text[i - 1].isWhitespace()) i--
            // The window may have cut the first word in half; never match on a word fragment.
            if (i == 0 && truncated) break
            chunkTokens(text, i, chunkEnd).asReversed().forEach { tokens.addFirst(it) }
            var gapHasNewline = false
            while (i > 0 && text[i - 1].isWhitespace()) {
                if (text[i - 1] == '\n') gapHasNewline = true
                i--
            }
            if (gapHasNewline) break
        }
        return if (tokens.size > MAX_WORDS + MAX_PARTICLES) tokens.toList().takeLast(MAX_WORDS + MAX_PARTICLES) else tokens
    }

    /** The words of one whitespace-free chunk [start, end), in order. */
    private fun chunkTokens(text: String, start: Int, end: Int): List<Token> {
        val lower = normalize(text.substring(start, end))
        if (EMOTICON.matches(lower)) return listOf(Token(lower, start))
        val words = mutableListOf<Token>()
        var i = start
        while (i < end) {
            if (!isWordChar(text[i])) {
                i++
                continue
            }
            val s = i
            while (i < end && isWordChar(text[i])) i++
            words += Token(normalize(text.substring(s, i)), s)
        }
        return words.ifEmpty { listOf(Token("", start)) }
    }

    /** Letters, digits and combining marks (text typed or pasted in decomposed form). */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    // -- Matching ------------------------------------------------------------------------------------------------

    private fun tagsFor(words: List<String>): List<StickerTag>? {
        if (words.any { it.isEmpty() }) return null
        KEYWORDS_BY_FOLD[words.joinToString(" ") { fold(it) }]
            ?.firstOrNull { keyword -> keyword.words.indices.all { wordMatches(words[it], keyword.words[it], keyword.accentRequired) } }
            ?.let { return it.tags }
        if (words.size == 1) {
            val folded = fold(words[0])
            PATTERNS.firstOrNull { (regex, _) -> regex.matches(folded) }?.let { return listOf(it.second) }
        }
        return null
    }

    /** Typed exactly, or typed with no marks at all and equal once the keyword's marks are removed. */
    private fun wordMatches(typed: String, keyword: String, accentRequired: Boolean): Boolean =
        typed == keyword || (!accentRequired && fold(typed) == typed && fold(keyword) == typed)

    private fun isParticle(word: String): Boolean {
        val folded = fold(word)
        return folded in PARTICLES || PARTICLE_PATTERN.matches(folded)
    }

    /** Lower case, composed (NFC) so typed and listed marks compare equal. */
    private fun normalize(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFC)

    /** Lower case without Vietnamese marks; "đ" becomes "d". */
    internal fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace('đ', 'd')

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Tokens kept whole instead of being trimmed to their letters: ":)))", "=))", ":(", "<3", "T_T". */
    private val EMOTICON = Regex("""[:=;x][-']?[)(\]\[dpo3]+|<3+|t[_.]t""")

    // -- Vocabulary ----------------------------------------------------------------------------------------------

    private class Keyword(val words: List<String>, val accentRequired: Boolean, val tags: List<StickerTag>)

    private val KEYWORDS: List<Keyword> = StickerKeywords.entries.flatMap { entry ->
        entry.phrases.map { phrase ->
            Keyword(phrase.trim().split(Regex("\\s+")).map(::normalize), entry.accentRequired, entry.tags)
        }
    }

    private val KEYWORDS_BY_FOLD: Map<String, List<Keyword>> = KEYWORDS.groupBy { k -> k.words.joinToString(" ") { fold(it) } }

    private val PATTERNS: List<Pair<Regex, StickerTag>> = StickerKeywords.patterns.map { (pattern, tag) -> Regex(pattern) to tag }

    private val PARTICLES: Set<String> = StickerKeywords.particles.map(::fold).toSet()

    /** Stretched particles: "nhaaa", "nhéee". */
    private val PARTICLE_PATTERN = Regex("nh[ae]{2,}")

    /** Every tag that has at least one keyword or pattern (for tests). */
    internal val coveredTags: Set<StickerTag>
        get() = (KEYWORDS.flatMap { it.tags } + PATTERNS.map { it.second }).toSet()
}
