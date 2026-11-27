/*
 * Copyright (C) 2026 AIChat
 */
package com.aichat.vi

import java.text.Normalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ViTextTest {

    @ParameterizedTest
    @CsvSource("được,duoc", "Người,nguoi", "ĐẤY,day", "ở,o", "khoẻ,khoe")
    fun `fold removes tones and marks`(input: String, expected: String) =
        assertEquals(expected, ViText.fold(input))

    @Test
    fun `fold handles NFD input`() {
        val nfd = Normalizer.normalize("được", Normalizer.Form.NFD)
        assertEquals("duoc", ViText.fold(nfd))
    }

    @Test
    fun `fold leaves ascii untouched`() = assertEquals("ok", ViText.fold("ok"))

    @ParameterizedTest
    @CsvSource("hòa,hoà", "thủy,thuỷ", "khỏe,khoẻ", "hoà,hoà")
    fun `withToneStyle modern`(input: String, expected: String) =
        assertEquals(expected, ViText.withToneStyle(input, ToneStyle.MODERN))

    @ParameterizedTest
    @CsvSource("hoà,hòa", "thuỷ,thủy", "hòa,hòa")
    fun `withToneStyle classic`(input: String, expected: String) =
        assertEquals(expected, ViText.withToneStyle(input, ToneStyle.CLASSIC))

    @ParameterizedTest
    @ValueSource(strings = ["hoàn", "quý", "già", "giữa"])
    fun `withToneStyle keeps closed and qu gi syllables`(word: String) {
        assertEquals(word, ViText.withToneStyle(word, ToneStyle.MODERN))
        assertEquals(word, ViText.withToneStyle(word, ToneStyle.CLASSIC))
    }

    @Test
    fun `withToneStyle keeps case`() {
        assertEquals("Hòa", ViText.withToneStyle("Hoà", ToneStyle.CLASSIC))
        assertEquals("HOÀ", ViText.withToneStyle("HÒA", ToneStyle.MODERN))
    }

    @Test
    fun `withToneStyle leaves invalid input`() {
        assertEquals("abcxyz", ViText.withToneStyle("abcxyz", ToneStyle.CLASSIC))
        assertEquals("abcxyz", ViText.withToneStyle("abcxyz", ToneStyle.MODERN))
    }
}
