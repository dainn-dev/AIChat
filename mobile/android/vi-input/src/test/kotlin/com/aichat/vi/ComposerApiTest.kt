package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ComposerApiTest {

    @Test
    fun `states are immutable snapshots`() {
        val first = telex.process(ComposerState.EMPTY, "toi")
        val second = telex.process(first, 's')
        assertEquals("toi", first.composingText)
        assertEquals("tói", second.composingText)
        assertEquals("tois", second.rawText)
        assertEquals("tói", telex.process(first, 's').composingText)
    }

    @Test
    fun `empty state`() {
        assertTrue(ComposerState.EMPTY.isEmpty)
        assertEquals("", telex.commitText(ComposerState.EMPTY))
        assertSame(ComposerState.EMPTY, telex.backspace(ComposerState.EMPTY))
        assertSame(ComposerState.EMPTY, telex.backspace(telex.process(ComposerState.EMPTY, 'a')))
        assertFalse(telex.process(ComposerState.EMPTY, 'a').isEmpty)
    }

    @Test
    fun `composing text falls back to the raw keys while the word is not Vietnamese`() {
        val state = telex.run("awes")
        assertEquals("awes", state.composingText)
        assertEquals("awes", state.rawText)
        assertEquals("ă", telex.compose("aw"))
    }

    @Test
    fun `fallback can be disabled`() {
        assertEquals("ừii", telexRaw.commit("wifi"))
        assertEquals("wifi", telex.commit("wifi"))
    }

    @Test
    fun `toString is readable`() {
        assertTrue(telex.run("as").toString().contains("composing=á"))
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "tooi laf nguowif Vieetj Nam.|tôi là người Việt Nam.",
            "xin chaof, banj khoer khoong?|xin chào, bạn khoẻ không?",
            "class street awesome window|class street awesome window",
            "ddc roofi!|đc rồi!",
            "toi 2 nawm|toi 2 năm",
            "hello, nguowif|hello, người",
        ],
    )
    fun `typing a sentence commits word by word`(keys: String, expected: String) =
        assertEquals(expected, telex.typeAll(keys))

    @ParameterizedTest
    @ValueSource(chars = [' ', '.', ',', '!', '?', '1', '9', '0', '\n', '(', '"'])
    fun `telex word breaks`(key: Char) = assertTrue(telex.isWordBreak(key))

    @ParameterizedTest
    @ValueSource(chars = ['a', 'Z', 'w', 'đ', 'ư', 'Ơ', 'á'])
    fun `telex word keys`(key: Char) = assertTrue(telex.isWordKey(key))

    @ParameterizedTest
    @ValueSource(chars = ['0', '1', '5', '6', '9'])
    fun `digits are word keys in VNI`(key: Char) {
        assertTrue(vni.isWordKey(key))
        assertTrue(telex.isWordBreak(key))
    }

    @Test
    fun `non-word keys inside process are kept literally`() {
        assertEquals("tôi là", telex.commit("tooi laf"))
        assertEquals("tôi là", telex.compose("tooi laf"))
        assertEquals("tooi laf", telex.run("tooi laf").rawText)
        assertEquals("hôm", telex.commit("tooi.⌫⌫⌫⌫hoom"))
    }

    @Test
    fun `typing Vietnamese letters directly works`() {
        assertEquals("tối", telexClassicRaw.compose("tôifs"))
        assertEquals("tồi", telexClassicRaw.compose("tốif"))
        assertEquals("tôi", telexClassicRaw.compose("tốiz"))
    }

    @Test
    fun `type helper resets after a break`() {
        val afterWord = telex.process(ComposerState.EMPTY, "chaof")
        val outcome = telex.type(afterWord, ' ')
        assertEquals("chào ", outcome.committed)
        assertSame(ComposerState.EMPTY, outcome.state)
        val letter = telex.type(ComposerState.EMPTY, 'a')
        assertEquals("", letter.committed)
        assertEquals("a", letter.state.composingText)
    }

    @Test
    fun `composer exposes its configuration`() {
        val composer = VietnameseComposer(InputMethod.VNI, ToneStyle.CLASSIC, autoRestoreEnglish = false)
        assertEquals(InputMethod.VNI, composer.inputMethod)
        assertEquals(ToneStyle.CLASSIC, composer.toneStyle)
        assertFalse(composer.autoRestoreEnglish)
    }
}
