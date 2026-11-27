/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import com.aichat.vi.ToneStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViGlideRankerTest {
    // Arithmetic (smoothing 5, Pu = count / total, Pu is ~1e-1..1e-4, so 5*Pu << 1):
    //  - (không,được)=700 vs (không,dược)=5 over total(không)=705+: P ~ 0.99 vs 0.007 -> được.
    //  - (thuốc,dược)=300 vs (thuốc,được)=10, total(thuốc)=310: P ~ 0.95 vs 0.03 -> dược flips
    //    although được has the higher unigram (3000 > 500).
    //  - (có,thể)=400, total(có)=400: P(thể|có) ~ 0.99; English "the" has no bigram, so
    //    P(the|có) = 5*Pu/405 ~ 0.001 -> thể beats "the" although "the" has a higher unigram (900 > 600).
    //  - Rank decay with no context: có (1500) at r=0 vs không (2000) at r=1 scores 1000 -> có wins
    //    since 2000/1500 < 2; hoà (60) at r=0 vs không at r=1 scores 1000 -> không wins.
    private val fixture = """
        U	được	3000
        U	dược	500
        U	đuốc	100
        U	không	2000
        U	thuốc	800
        U	có	1500
        U	thể	600
        U	hòa	60
        B	không	được	700
        B	không	dược	5
        B	thuốc	dược	300
        B	thuốc	được	10
        B	có	thể	400
    """.trimIndent()

    // "the" -> 0.3 * 255/255 * 3000 = 900 pseudo-count; "ok" -> 0.3 * 200/255 * 3000 ~ 706.
    private val english = mapOf("the" to 255, "ok" to 200)
    private val lexicon = ViGlideLexicon.parse(fixture.lineSequence(), english)

    private fun rank(folded: List<String>, prev: String?, style: ToneStyle = ToneStyle.MODERN) =
        ViGlideRanker.rank(folded, prev, lexicon, style)

    @Test
    fun `khong then duoc gives duoc`() {
        assertEquals("được", rank(listOf("duoc"), "không").first())
    }

    @Test
    fun `thuoc then duoc gives duoc with dot`() {
        assertEquals("dược", rank(listOf("duoc"), "thuốc").first())
    }

    @Test
    fun `no previous uses unigram`() {
        assertEquals(listOf("được", "dược", "đuốc"), rank(listOf("duoc"), null))
    }

    @Test
    fun `english word loses to vietnamese syllable after vietnamese context`() {
        val result = rank(listOf("the"), "có")
        assertEquals("thể", result.first())
        assertTrue(result.indexOf("thể") < result.indexOf("the"))
    }

    @Test
    fun `english only form returns the english word`() {
        assertEquals(listOf("ok"), rank(listOf("ok"), null))
    }

    @Test
    fun `classifier rank decays`() {
        assertEquals("có", rank(listOf("co", "khong"), null).first())
        assertEquals("không", rank(listOf("hoa", "khong"), null).first())
    }

    @Test
    fun `tone style applied to output`() {
        assertEquals(listOf("hoà"), rank(listOf("hoa"), null, ToneStyle.MODERN))
        assertEquals(listOf("hòa"), rank(listOf("hoa"), null, ToneStyle.CLASSIC))
    }

    @Test
    fun `english entries are not tone converted`() {
        assertEquals(listOf("the"), rank(listOf("the"), null).filter { it == "the" })
    }

    @Test
    fun `duplicate folded forms yield one result`() {
        assertEquals(listOf("hoà"), rank(listOf("hoa", "hoa"), null))
    }

    @Test
    fun `unknown folded form yields nothing`() {
        assertEquals(emptyList(), rank(listOf("zzz"), null))
    }

    @Test
    fun `at most 9 results and only first K folded forms considered`() {
        val lines = buildList {
            listOf("ba", "bá", "bà", "bả", "bã", "bạ").forEachIndexed { i, w -> add("U\t$w\t${600 - i}") }
            listOf("be", "bé", "bè", "bẻ", "bẽ", "bẹ").forEachIndexed { i, w -> add("U\t$w\t${500 - i}") }
            add("U\tbi\t100")
            add("U\tbo\t90")
            add("U\tbu\t80")
            add("U\tca\t1000000")
        }
        val lex = ViGlideLexicon.parse(lines.asSequence(), emptyMap())

        val result = ViGlideRanker.rank(listOf("ba", "be", "bi", "bo", "bu", "ca"), null, lex, ToneStyle.MODERN)

        assertEquals(ViGlideRanker.MAX_RESULTS, result.size)
        assertFalse("ca" in result)
        assertEquals("ba", result.first())
    }
}
