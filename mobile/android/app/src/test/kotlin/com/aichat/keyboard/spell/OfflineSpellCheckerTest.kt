package com.aichat.keyboard.spell

import com.aichat.keyboard.vi.glide.ViGlideLexicon
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineSpellCheckerTest {
    private val english = EnglishSpellDictionary(
        mapOf("hello" to 200, "world" to 150, "the" to 255, "then" to 100, "they" to 120, "there" to 90),
    )
    private val vietnamese = ViSpellDictionary(
        ViGlideLexicon.parse(
            sequenceOf("U\ttôi\t5000", "U\ttới\t3000", "U\ttối\t2000", "U\tngày\t4000", "U\tma\t100", "U\tmà\t900"),
            mapOf("ok" to 200),
        ),
    )

    private fun en(word: String) = OfflineSpellChecker.check(word, english)
    private fun vi(word: String) = OfflineSpellChecker.check(word, vietnamese)

    @Test
    fun `known words are valid, ignoring case`() {
        assertEquals(SpellVerdict.Valid, en("hello"))
        assertEquals(SpellVerdict.Valid, en("Hello"))
        assertEquals(SpellVerdict.Valid, vi("Tôi"))
    }

    @Test
    fun `an english typo gets the closest known words, most frequent first`() {
        assertEquals(SpellVerdict.Typo(listOf("hello")), en("helo"))
        assertEquals(SpellVerdict.Typo(listOf("world")), en("wrold"))
        // "ther" is one edit from "the", "then", "they" and "there".
        assertEquals(SpellVerdict.Typo(listOf("the", "they", "then", "there")), en("ther"))
    }

    @Test
    fun `a suggestion keeps the capitalisation of the typo`() {
        assertEquals(SpellVerdict.Typo(listOf("Hello")), en("Helo"))
    }

    @Test
    fun `a missing vietnamese tone mark offers every accented spelling`() {
        assertEquals(SpellVerdict.Typo(listOf("tôi", "tới", "tối")), vi("toi"))
        assertEquals(SpellVerdict.Typo(listOf("ngày")), vi("ngay"))
        assertEquals(SpellVerdict.Typo(listOf("Ngày")), vi("Ngay"))
    }

    @Test
    fun `a word with no confident correction is left alone`() {
        // Names, slang and brands missing from the list must not be underlined.
        assertEquals(SpellVerdict.Valid, en("Zxqvk"))
        assertEquals(SpellVerdict.Valid, vi("ko"))
        assertEquals(SpellVerdict.Valid, vi("Linh"))
    }

    @Test
    fun `numbers, links, mentions, acronyms and single letters are never checked`() {
        for (word in listOf("a", "2024", "abc123", "http://x.y", "me@x.com", "#tag", "NASA", "OK", "5kg")) {
            assertEquals(SpellVerdict.Valid, en(word), word)
        }
    }

    @Test
    fun `a word is never suggested as itself`() {
        assertEquals(SpellVerdict.Valid, OfflineSpellChecker.check("hello", english))
        val only = object : SpellDictionary {
            override fun contains(word: String) = false
            override fun suggestions(word: String, max: Int) = listOf("Word", "word")
        }
        assertEquals(SpellVerdict.Valid, OfflineSpellChecker.check("word", only))
    }
}
