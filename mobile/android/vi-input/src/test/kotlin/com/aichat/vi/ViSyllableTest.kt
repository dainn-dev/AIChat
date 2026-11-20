package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ViSyllableTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a", "ba", "người", "hương", "quốc", "giữ", "gìn", "nghiêng", "khuya", "hoà", "hòa", "thuỷ", "thủy",
            "oà", "việt", "đường", "chuyển", "tiếng", "không", "một", "ươ", "uống", "yêu", "khuếch", "Việt",
            "NGƯỜI", "gì", "quý", "đ", "nhà", "giếng", "Giữ",
        ],
    )
    fun `valid syllables`(word: String) = assertTrue(ViSyllable.isValid(word), word)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "class", "street", "awesome", "window", "hello", "bl", "xyz", "tôr", "àc", "mạch1", "ừa1", "nghi n",
            "càt", "bảp", "chòch", "aé", "f", "w", "tiéng",
        ],
    )
    fun `invalid syllables`(word: String) = assertFalse(ViSyllable.isValid(word), word)

    @ParameterizedTest
    @ValueSource(strings = ["ngh", "nguo", "tr", "kh", "qu", "vie", "nghie", "đươ"])
    fun `prefixes are accepted while typing`(word: String) =
        assertTrue(ViSyllable.isValid(word, complete = false), word)

    @Test
    fun `incomplete vowel is rejected when complete`() {
        assertFalse(ViSyllable.isValid("tieng", complete = true))
        assertTrue(ViSyllable.isValid("tieng", complete = false))
    }

    @ParameterizedTest(name = "{0} -> [{1}|{2}|{3}]")
    @CsvSource(
        "người, ng, ười, ''", "gia, gi, a, ''", "giếng, g, iế, ng", "quốc, qu, ố, c", "anh, '', a, nh",
        "nghiêng, ngh, iê, ng", "khuya, kh, uya, ''", "ch, ch, '', ''", "giữ, gi, ữ, ''",
    )
    fun `splits initial, vowel and final`(word: String, initial: String, vowel: String, final: String) {
        val parts = ViSyllable.split(word)
        assertEquals(SyllableParts(initial, vowel, final), parts)
    }
}
