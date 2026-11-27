/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi.glide

import com.aichat.keyboard.vi.ViInputGate
import dev.patrickgold.florisboard.ime.editor.InputAttributes.Variation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ViGlideRoutingTest {
    private val fixture = """
        U	được	3000
        U	dược	500
        U	không	2000
        B	không	được	700
    """.trimIndent()

    private fun lex() = ViGlideLexicon.parse(fixture.lineSequence(), mapOf("ok" to 200))

    @Test
    fun `vietnamese only for vi composer`() {
        assertTrue(ViGlideRouting.useVietnamese(isViComposer = true))
        assertFalse(ViGlideRouting.useVietnamese(isViComposer = false))
    }

    @Test
    fun `glide blocked in password email and url`() {
        for (variation in ViInputGate.BLOCKED_VARIATIONS) {
            assertFalse(ViGlideRouting.glideAllowed(isPassword = false, variation = variation), "$variation")
        }
        assertFalse(ViGlideRouting.glideAllowed(isPassword = true, variation = Variation.NORMAL))
        assertTrue(ViGlideRouting.glideAllowed(isPassword = false, variation = Variation.NORMAL))
        assertTrue(ViGlideRouting.glideAllowed(isPassword = false, variation = Variation.SHORT_MESSAGE))
    }

    @Test
    fun `candidates split commit and up to 8 suggestions with case applied`() {
        val ranked = listOf("được", "dược", "đuốc", "a", "b", "c", "d", "e", "f")

        val (commit, suggestions) = ViGlideRouting.candidates(ranked) { it.uppercase() }

        assertEquals("ĐƯỢC", commit)
        assertEquals(listOf("DƯỢC", "ĐUỐC", "A", "B", "C", "D", "E", "F"), suggestions)
    }

    @Test
    fun `candidates cap suggestions at 8 even for longer input`() {
        val ranked = (1..12).map { "w$it" }

        val (commit, suggestions) = ViGlideRouting.candidates(ranked) { it }

        assertEquals("w1", commit)
        assertEquals((2..9).map { "w$it" }, suggestions)
    }

    @Test
    fun `vietnamese lexicon not loaded means no Vietnamese suggestions and no English fallback`() {
        assertEquals(null to emptyList(), ViGlideRouting.candidates(emptyList()) { it })
    }

    @Test
    fun `previous text keeps one char beyond the syllable window`() {
        val long = "x".repeat(200) + " không "

        val window = ViGlideRouting.previousTextWindow(long)

        assertEquals(ViGlideRouting.PREVIOUS_TEXT_WINDOW + 1, window.length)
        assertEquals(long.takeLast(window.length), window.toString())
        assertEquals("ab", ViGlideRouting.previousTextWindow("ab").toString())
    }

    @Test
    fun `classifier lexicon adapter exposes folded forms and folded frequency`() {
        val lexicon = lex()

        val adapter = ViGlideClassifierLexicon(lexicon)

        assertEquals(lexicon.foldedForms, adapter.words)
        assertEquals(lexicon.foldedFrequency("duoc"), adapter.frequency("duoc"))
        assertEquals(0.0, adapter.frequency("zzz"))
    }

    @Test
    fun `adapter identity follows the wrapped lexicon`() {
        val a = lex()
        val b = lex()

        assertEquals(ViGlideClassifierLexicon(a), ViGlideClassifierLexicon(a))
        assertNotEquals(ViGlideClassifierLexicon(a), ViGlideClassifierLexicon(b))
    }
}
