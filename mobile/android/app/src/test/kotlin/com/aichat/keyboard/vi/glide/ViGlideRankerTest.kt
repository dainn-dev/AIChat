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
    fun `english entries are returned verbatim under both styles`() {
        // English is a-z only, so no tone conversion could change it today; this pins the contract.
        for (style in ToneStyle.values()) assertEquals(listOf("ok"), rank(listOf("ok"), null, style))
    }

    @Test
    fun `duplicate folded forms yield one result`() {
        // Stored as both hòa and hoà; they merge, and a repeated folded form must not repeat the output.
        val lex = ViGlideLexicon.parse(sequenceOf("U\thòa\t60", "U\thoà\t40"), emptyMap())
        for ((style, expected) in listOf(ToneStyle.MODERN to "hoà", ToneStyle.CLASSIC to "hòa")) {
            assertEquals(listOf(expected), ViGlideRanker.rank(listOf("hoa", "hoa"), null, lex, style))
        }
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

    private fun lex(vararg lines: String) = ViGlideLexicon.parse(lines.asSequence(), emptyMap())

    @Test
    fun `smoothing term decides when the previous syllable has few bigrams`() {
        // me -> bá seen once (total(me)=1). Pu(bá)=10/9110 ~ 0.001, Pu(bả)=9000/9110 ~ 0.988.
        // P(bá|me) = (1 + 5*0.001) / 6 ~ 0.168    P(bả|me) = (0 + 5*0.988) / 6 ~ 0.823 -> bả first.
        // SMOOTHING=0 would give bá 1.0 vs bả 0 and flip the order; SMOOTHING=1 gives 0.50 vs 0.49.
        val l = lex("U\tme\t100", "U\tbá\t10", "U\tbả\t9000", "B\tme\tbá\t1")
        assertEquals(listOf("bả", "bá"), ViGlideRanker.rank(listOf("ba"), "me", l, ToneStyle.MODERN))
    }

    @Test
    fun `previous syllable without bigrams falls back to unigram`() {
        // total(me)=0: P = 5*Pu/5 = Pu -> bả (9000) before bá (10). With SMOOTHING=0 every score is 0,
        // and with the "+ 5" dropped it is 0/0 = NaN; both tie and fall back to word order (bá first).
        val l = lex("U\tme\t100", "U\tbá\t10", "U\tbả\t9000")
        assertEquals(listOf("bả", "bá"), ViGlideRanker.rank(listOf("ba"), "me", l, ToneStyle.MODERN))
    }

    @Test
    fun `rank decay factor is exactly one half`() {
        // No context, so score = f^r * count (same total). f=0.5:
        //   pair 1: be(3000) at r=1 scores 1500 > ba(1000) at r=0 -> be first. f=0.25 gives 750 -> ba first.
        //   pair 2: bo(1700) at r=1 scores 850 < bi(1000) at r=0 -> bi first. f=0.6 gives 1020 -> bo first.
        val l = lex("U\tba\t1000", "U\tbe\t3000", "U\tbi\t1000", "U\tbo\t1700")
        assertEquals(listOf("be", "ba"), ViGlideRanker.rank(listOf("ba", "be"), null, l, ToneStyle.MODERN))
        assertEquals(listOf("bi", "bo"), ViGlideRanker.rank(listOf("bi", "bo"), null, l, ToneStyle.MODERN))
    }

    @Test
    fun `exactly the first five folded forms contribute`() {
        val l = lex("U\tba\t10", "U\tbe\t10", "U\tbi\t10", "U\tbo\t10", "U\tbu\t10", "U\tca\t10")
        val result = ViGlideRanker.rank(listOf("ba", "be", "bi", "bo", "bu", "ca"), null, l, ToneStyle.MODERN)
        assertEquals(listOf("ba", "be", "bi", "bo", "bu"), result) // 5th ("bu") in, 6th ("ca") out
    }
}
