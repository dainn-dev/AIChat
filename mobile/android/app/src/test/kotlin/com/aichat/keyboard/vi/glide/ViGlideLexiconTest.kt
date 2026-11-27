/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViGlideLexiconTest {
    private val fixture = """
        U	được	3000
        U	đuốc	100
        U	dược	500
        U	hòa	40
        U	hoà	60
        U	không	2000
        U	xyz	999
        U	đđ	999
        U	iphone	999
        B	không	được	700
        B	không	dược	5
        B	hòa	không	3
        B	hoà	không	4
        B	không	đuốc	1
    """.trimIndent()

    private fun lex(english: Map<String, Int> = mapOf("ok" to 200, "hello" to 100)) =
        ViGlideLexicon.parse(fixture.lineSequence(), english)

    @Test
    fun `parse groups syllables by folded form`() {
        val words = lex().expansions("duoc").map { it.word }
        assertEquals(listOf("được", "dược", "đuốc"), words)
    }

    @Test
    fun `classic and modern spellings merge`() {
        val hoa = lex().expansions("hoa").filter { !it.english }
        assertEquals(listOf("hoà"), hoa.map { it.word })
        assertEquals(100L, hoa.single().count)
    }

    @Test
    fun `invalid syllables are dropped at load`() {
        val l = lex()
        assertFalse(l.isKnown("xyz"))
        assertFalse(l.isKnown("đđ"))
        assertFalse(l.isKnown("iphone"))
        assertTrue(l.expansions("iphone").isEmpty())
    }

    @Test
    fun `english top words are folded to themselves with reduced weight`() {
        val ok = lex().expansions("ok").single()
        assertTrue(ok.english)
        assertEquals("ok", ok.word)
        assertTrue(ok.count <= 0.3 * 3000)
        assertTrue("ok" in lex().foldedForms)
    }

    @Test
    fun `english count is exactly weighted and scaled`() {
        // max Vietnamese count is 3000; round(0.3 * 200 / 255 * 3000) = 706
        assertEquals(706L, lex().expansions("ok").single().count)
        assertEquals(353L, lex().expansions("hello").single().count)
    }

    @Test
    fun `unigram probability divides by total including english`() {
        // vi: 3000 + 100 + 500 + 100 (merged hoa) + 2000 = 5700; en: 706 + 353
        val total = 5700.0 + 706 + 353
        assertEquals(706 / total, lex().unigramProbability("ok"), 1e-12)
        assertEquals(3000 / total, lex().unigramProbability("được"), 1e-12)
    }

    @Test
    fun `english word equal to vietnamese syllable is merged once`() {
        val l = ViGlideLexicon.parse(
            sequenceOf("U	the	1000", "U	thể	50"),
            mapOf("the" to 255),
        )
        val the = l.expansions("the").filter { it.word == "the" }
        assertEquals(1, the.size)
        assertFalse(the.single().english)
        assertEquals(1000L + 300L, the.single().count)
    }

    @Test
    fun `different english and vietnamese words share a folded key`() {
        val l = ViGlideLexicon.parse(
            sequenceOf("U	thể	1000", "U	thế	900"),
            mapOf("the" to 255),
        )
        val entries = l.expansions("the")
        assertEquals(setOf("thể", "thế", "the"), entries.map { it.word }.toSet())
        assertTrue(entries.single { it.word == "the" }.english)
    }

    @Test
    fun `english entries with zero weight are dropped`() {
        assertTrue(lex(mapOf("zero" to 0)).expansions("zero").isEmpty())
    }

    @Test
    fun `english beyond top 2000 is excluded`() {
        val english = (0 until 2100).associate { i -> "w" + ('a' + i / 26 / 26 % 26) + ('a' + i / 26 % 26) + ('a' + i % 26) to 255 - i / 10 } +
            mapOf("zzz9" to 255, "Upper" to 255)
        val l = ViGlideLexicon.parse(fixture.lineSequence(), english)
        val kept = english.keys.count { l.expansions(it).any { e -> e.english } }
        assertEquals(2000, kept)
        assertTrue(l.expansions("zzz9").isEmpty())
    }

    @Test
    fun `bigram lookups use canonical forms`() {
        val l = lex()
        assertEquals(700L, l.bigramCount("không", "được"))
        assertEquals(7L, l.bigramCount("hoà", "không"))
        assertEquals(7L, l.bigramCount("hòa", "không"))
        assertEquals(0L, l.bigramCount("không", "hòa"))
        assertEquals(706L, l.bigramTotal("không"))
        assertEquals(0L, l.bigramTotal("nothing"))
    }

    @Test
    fun `foldedFrequency is within 0 to 1 and highest for the most frequent form`() {
        val l = lex()
        l.foldedForms.forEach { assertTrue(l.foldedFrequency(it) in 0.0..1.0) }
        val best = l.foldedForms.maxBy { l.foldedFrequency(it) }
        assertEquals("duoc", best)
        assertEquals(1.0, l.foldedFrequency("duoc"), 1e-9)
        assertTrue(l.foldedFrequency("khong") < 1.0)
        assertEquals(0.0, l.foldedFrequency("qqq"))
    }

    @Test
    fun `isKnown accepts both tone styles and english`() {
        val l = lex()
        assertTrue(l.isKnown("hòa"))
        assertTrue(l.isKnown("hoà"))
        assertTrue(l.isKnown("Được"))
        assertTrue(l.isKnown("OK"))
        assertFalse(l.isKnown("khong x"))
    }

    @Test
    fun `unigram probability uses canonical form`() {
        val l = lex()
        assertTrue(l.unigramProbability("hòa") > 0.0)
        assertEquals(l.unigramProbability("hòa"), l.unigramProbability("hoà"), 0.0)
        assertEquals(0.0, l.unigramProbability("nope"))
    }

    @Test
    fun `parses the real asset`() {
        val assets = findAssets()
        val english = Json.decodeFromString(
            MapSerializer(String.serializer(), Int.serializer()),
            File(assets, "ime/dict/data.json").readText(),
        )
        val file = File(assets, "ime/dict/vi_glide.tsv")
        val t0 = System.nanoTime()
        val l = file.useLines(Charsets.UTF_8) { ViGlideLexicon.parse(it, english) }
        println("vi_glide parse time: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertTrue(l.expansions("duoc").any { it.word == "được" })
        assertTrue(l.bigramCount("không", "được") > 0)
        assertTrue(l.expansions("ok").any { it.english })
        val viUnigrams = file.useLines(Charsets.UTF_8) { it.count { line -> line.startsWith("U\t") } }
        assertTrue(viUnigrams in 8_000..10_000, "U lines: $viUnigrams")
        assertTrue(l.foldedForms.size > 2_000, "folded forms: ${l.foldedForms.size}")
        assertTrue(l.bigramTotal("không") > 0)
    }

    private fun findAssets(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets")
            if (File(candidate, "ime/dict/vi_glide.tsv").exists()) return candidate
            val local = File(dir, "src/main/assets")
            if (File(local, "ime/dict/vi_glide.tsv").exists()) return local
            dir = dir.parentFile
        }
        error("assets not found")
    }
}
