package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class VniTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "a1, á", "a2, à", "a3, ả", "a4, ã", "a5, ạ",
        "a6, â", "a8, ă", "e6, ê", "o6, ô", "o7, ơ", "u7, ư", "d9, đ",
        "vie6t5, việt", "nguo7i2, người", "nguoi72, người", "huo7ng, hương", "d9uong72, đường",
        "to6i1, tối", "Vie6t5, Việt", "D9i, Đi", "tie6ng1, tiếng", "chuye6n3, chuyển",
        "mo65t, một", "khong6, không", "ca1m o7n, cám ơn",
    )
    fun `tones and marks`(keys: String, expected: String) = assertEquals(expected, vni.typeAll(keys))

    @ParameterizedTest(name = "{0} -> modern {1} / classic {2}")
    @CsvSource(
        "hoa2, hoà, hòa", "thuy3, thuỷ, thủy", "khoe3, khoẻ, khỏe", "quo6c1, quốc, quốc",
        "giu74, giữ, giữ", "gi2n, gìn, gìn", "oa2, oà, òa", "nghie6ng4, nghiễng, nghiễng",
    )
    fun `tone placement for both styles`(keys: String, modern: String, classic: String) {
        assertEquals(modern, vni.commit(keys), "modern")
        assertEquals(classic, vniClassic.commit(keys), "classic")
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("a11, a1", "o55, o5", "d99, d9", "a66, a6", "a10, a", "lo10, lo", "chuye6n30, chuyên")
    fun `double key undo and tone removal`(keys: String, expected: String) =
        assertEquals(expected, VietnameseComposer(InputMethod.VNI, autoRestoreEnglish = false).commit(keys))

    @ParameterizedTest(name = "{0} stays {0}")
    @CsvSource("class", "street", "awesome", "window", "hello", "wow")
    fun `letters are never transformed in VNI`(word: String) = assertEquals(word, vni.commit(word))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("vie6t5⌫n, viện", "nguo7i2⌫, ngườ", "a1⌫, ''", "d9⌫, ''")
    fun backspace(script: String, expected: String) = assertEquals(expected, vni.compose(script))
}
