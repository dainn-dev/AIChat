/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreviousSyllableTest {
    private val lexicon = ViGlideLexicon.parse(
        sequenceOf("U\tkhông\t2000", "U\tviệt\t100"),
        mapOf("ok" to 200),
    )

    private fun of(text: String) = PreviousSyllable.of(text, lexicon::isKnown)

    @Test
    fun `trailing space exposes the word`() {
        assertEquals("không", of("Không "))
        assertEquals("không", of("Không  "))
    }

    @Test
    fun `mid word has no previous syllable`() {
        assertNull(of("Không"))
    }

    @Test
    fun `punctuation digit emoji newline and empty give null`() {
        assertNull(of("không, "))
        assertNull(of("2 "))
        assertNull(of("😀 "))
        assertNull(of(""))
        assertNull(of("a\n"))
        assertNull(of("   "))
    }

    @Test
    fun `english entries count`() {
        assertEquals("ok", of("ok "))
    }

    @Test
    fun `previous syllable ignores non-Vietnamese tokens`() {
        assertNull(of("https://x.vn "))
        assertNull(of("zzz "))
        assertNull(of("x/không "))
        assertNull(of("2không "))
    }

    @Test
    fun `previous syllable is lowercased`() {
        assertEquals("không", of("KHÔNG "))
    }

    @Test
    fun `decomposed input is normalised`() {
        assertEquals("việt", of("Vie\u0302\u0323t "))
    }

    @Test
    fun `only the last word counts`() {
        assertEquals("không", of("zzz không "))
    }

    @Test
    fun `first word of a new line is context`() {
        assertEquals("không", of("a\nkhông "))
        assertEquals("không", of("a\r\nkhông "))
    }

    @Test
    fun `newline between token and cursor gives null`() {
        assertNull(of("không\n "))
        assertNull(of("không\n"))
    }

    @Test
    fun `opening punctuation before the token is allowed`() {
        for (opener in listOf("(", "[", "\"", "“", "'", "‘")) {
            assertEquals("không", of("${opener}không "), "opener $opener")
        }
    }

    @Test
    fun `other characters before the token give null`() {
        for (c in listOf("-", ".", ")", "#", "@", "”")) {
            assertNull(of("${c}không "), "char $c")
        }
    }

    @Test
    fun `long line ending in a known word returns the word`() {
        assertEquals("không", of("x".repeat(200) + " không "))
    }

    @Test
    fun `over-long token returns null`() {
        assertNull(PreviousSyllable.of("a".repeat(100) + " ") { true })
    }
}
