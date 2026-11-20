package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource

/**
 * Cases from upstream bamboo_test.go, run with the spell-check fallback off so the output is the
 * raw engine result. Upstream uses "Telex 2" with the standard (classic) tone style; bracket keys
 * of Telex 2 are not part of this port. Cases that do not depend on the tone style also run in
 * the modern style.
 */
class BambooParityTest {

    @ParameterizedTest(name = "classic {0} -> {1}")
    @MethodSource("styleIndependentCases")
    fun `upstream cases in classic style`(script: String, expected: String) =
        assertEquals(expected, telexClassicRaw.compose(script))

    @ParameterizedTest(name = "modern {0} -> {1}")
    @MethodSource("styleIndependentCases")
    fun `upstream cases in modern style`(script: String, expected: String) =
        assertEquals(expected, telexRaw.compose(script))

    @ParameterizedTest(name = "{0} -> classic {1} / modern {2}")
    @CsvSource(
        "loanj⌫, lọa, loạ", "turyn⌫⌫, tủ, tu", "choas, chóa, choá", "bieecs, biếc, biếc", "uese, uế, uế",
        "tieens⌫, tiế, tiế", "Vieetj⌫, Việ, Việ",
    )
    fun `style dependent upstream cases`(script: String, classic: String, modern: String) {
        assertEquals(classic, telexClassicRaw.compose(script), "classic")
        assertEquals(modern, telexRaw.compose(script), "modern")
    }

    companion object {
        @JvmStatic
        fun styleIndependentCases(): List<Arguments> = listOf(
            "aw" to "ă", "uwow" to "ươ", "chuaarn" to "chuẩn", "giamaf" to "giầm", "ddafi" to "đài",
            "mootj" to "một", "Thuow" to "Thuơ", "VIEETJ" to "VIỆT", "noww" to "now", "sawss" to "săs",
            "dd" to "đ", "SDD" to "SĐ", "wowfi" to "ười", "catr" to "catr", "toowi" to "tơi", "aloo" to "alô",
            "tooss" to "tôs", "tosos" to "tôs", "ww" to "w", "wiw" to "uiw", "duwoi" to "dươi", "reff" to "ref",
            "oddp" to "ođp", "gisa" to "giá", "kimso" to "kímo", "toorr" to "tôr", "tnoss" to "tnos",
            "ddawks" to "đắk", "NGUOIW" to "NGƯƠI", "duwongwj" to "duongwj", "loz" to "loz", "losz" to "lo",
            "linuxx" to "linux", "buwoo" to "buô", "buowco" to "buôc", "cuoiwo" to "cuôi", "acha" to "acha",
            "nhuw" to "như", "tooi" to "tôi", "nhueej" to "nhuệ", "cuongw" to "cương", "quawcj" to "quặc",
            "hanhj⌫" to "hạn", "Thuow⌫" to "Thu", "reff⌫f" to "rè", "VIEETJ⌫" to "VIỆ", "acf" to "acf",
            "acs" to "ác", "hocj" to "học", "hocf" to "hocf", "ichf" to "ichf", "ichj" to "ịch", "ds" to "ds",
            "z" to "z",
        ).map { (script, expected) -> Arguments.of(script, expected) }
    }
}
