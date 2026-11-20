package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random

class ReviewFixesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "tieens⌫, tiế", "bieenr⌫, biể", "Vieetj⌫, Việ", "yeeus⌫, yế", "kieeus⌫, kiế", "Vieej, Việ",
        "tieesng, tiếng", "cuoons⌫, cuố", "muwowns⌫, mướ", "nguowif⌫, ngườ", "tieenf, tiền",
    )
    fun `modern style keeps the tone on the marked second vowel of open iê yê uô ươ`(script: String, expected: String) {
        assertEquals(expected, telex.compose(script), "telex modern")
        assertEquals(expected, telexClassic.compose(script), "telex classic")
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("Vie6t5⌫, Việ", "tie6n1⌫, tiế", "bie6n3⌫, biể", "ye6u1⌫, yế", "cuo6n1⌫, cuố", "Vie65, Việ")
    fun `modern style vni`(script: String, expected: String) {
        assertEquals(expected, vni.compose(script), "vni modern")
        assertEquals(expected, vniClassic.compose(script), "vni classic")
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("www", "Www", "WWW", "wwww", "awww", "ddd")
    fun `repeated keys are never lost in raw text`(keys: String) = assertEquals(keys, telex.run(keys).rawText)

    @Test
    fun `www keeps all three letters`() {
        assertEquals("www", telex.commit("www"))
        assertEquals("Www", telex.commit("Www"))
        assertEquals("www", telex.compose("www"))
        assertEquals("www.google.com", telex.typeAll("www.google.com"))
        assertEquals("ww", telex.run("ww").rawText)
    }

    @Test
    fun `rawText always equals the keys typed`() {
        val random = Random(20261120)
        val telexKeys = "aeiouyAEOUWwdDsfrxjzSFRXJZnghtcqkm "
        val vniKeys = "aeiouyAEOdDnghtcm0123456789 "
        repeat(PROPERTY_RUNS) {
            val telexInput = randomKeys(random, telexKeys)
            assertEquals(telexInput, telex.run(telexInput).rawText, "telex: $telexInput")
            assertEquals(telexInput, telexClassicRaw.run(telexInput).rawText, "telex raw: $telexInput")
            val vniInput = randomKeys(random, vniKeys)
            assertEquals(vniInput, vni.run(vniInput).rawText, "vni: $vniInput")
        }
    }

    @Test
    fun `type force-commits a word at the length cap`() {
        val max = VietnameseComposer.MAX_WORD_KEYS
        var state = ComposerState.EMPTY
        val committed = StringBuilder()
        repeat(max) {
            val outcome = telex.type(state, 'b')
            assertEquals("", outcome.committed)
            state = outcome.state
        }
        assertEquals(max, state.rawText.length)
        val overflow = telex.type(state, 'b')
        committed.append(overflow.committed)
        assertEquals("b".repeat(max), committed.toString())
        assertEquals("b", overflow.state.rawText)
        assertEquals("b".repeat(max + 10), telex.typeAll("b".repeat(max + 10)))
    }

    @Test
    fun `lone surrogate halves are rejected and emoji are committed whole`() {
        val word = telex.process(ComposerState.EMPTY, "chaof")
        assertThrows(IllegalArgumentException::class.java) { telex.type(word, '\uD83D') }
        assertThrows(IllegalArgumentException::class.java) { telex.type(word, '\uDE00') }
        assertThrows(IllegalArgumentException::class.java) { telex.process(word, '\uD83D') }
        val emoji = telex.type(word, "😀")
        assertEquals("chào😀", emoji.committed)
        assertSame(ComposerState.EMPTY, emoji.state)
        val letter = telex.type(word, "s")
        assertEquals("", letter.committed)
        assertEquals("cháo", letter.state.composingText)
        assertEquals("chào ", telex.type(word, " ").committed)
    }

    @Test
    fun `states have value equality`() {
        val a = telex.process(ComposerState.EMPTY, "as")
        val b = telex.process(ComposerState.EMPTY, "as")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, telex.process(ComposerState.EMPTY, "af"))
        assertFalse(a.equals("á"))
        assertEquals(ComposerState.EMPTY, telex.backspace(a))
    }

    @ParameterizedTest(name = "{0}: composing {1}, committed {2}")
    @CsvSource("user, uẻ, user", "tiengs, tiéng, tiengs", "tieng, tieng, tieng", "huowng⌫⌫, hươ, hươ")
    fun `documented composing versus committed differences`(script: String, composing: String, committed: String) {
        val state = telex.run(script)
        assertEquals(composing, state.composingText)
        assertEquals(committed, telex.commitText(state))
    }

    private fun randomKeys(random: Random, alphabet: String): String =
        String(CharArray(random.nextInt(1, MAX_RANDOM_LENGTH)) { alphabet[random.nextInt(alphabet.length)] })

    private companion object {
        const val PROPERTY_RUNS = 400
        const val MAX_RANDOM_LENGTH = 14
    }
}
