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
}
