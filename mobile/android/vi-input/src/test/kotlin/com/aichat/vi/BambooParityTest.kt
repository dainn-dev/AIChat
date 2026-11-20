package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Cases from upstream bamboo_test.go, run with the spell-check fallback off so the output is the
 * raw engine result. Upstream uses "Telex 2" with the standard (classic) tone style; bracket keys
 * of Telex 2 are not part of this port.
 */
class BambooParityTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "aw, ă", "uwow, ươ", "chuaarn, chuẩn", "giamaf, giầm", "ddafi, đài", "mootj, một",
        "Thuow, Thuơ", "VIEETJ, VIỆT", "noww, now", "sawss, săs", "dd, đ", "SDD, SĐ", "wowfi, ười",
        "catr, catr", "toowi, tơi", "aloo, alô", "tooss, tôs", "tosos, tôs", "ww, w", "wiw, uiw",
        "duwoi, dươi", "reff, ref", "oddp, ođp", "gisa, giá", "kimso, kímo", "toorr, tôr", "tnoss, tnos",
        "ddawks, đắk", "NGUOIW, NGƯƠI", "duwongwj, duongwj", "loz, loz", "losz, lo", "linuxx, linux",
        "buwoo, buô", "buowco, buôc", "cuoiwo, cuôi", "acha, acha", "nhuw, như", "tooi, tôi",
        "nhueej, nhuệ", "cuongw, cương", "quawcj, quặc", "hanhj⌫, hạn", "loanj⌫, lọa", "Thuow⌫, Thu",
        "reff⌫f, rè", "VIEETJ⌫, VIỆ", "turyn⌫⌫, tủ",
        "acf, acf", "acs, ác", "hocj, học", "hocf, hocf", "ichf, ichf", "ichj, ịch", "ds, ds", "z, z",
    )
    fun `upstream classic-style cases`(script: String, expected: String) =
        assertEquals(expected, telexClassicRaw.compose(script))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("choas, choá", "bieecs, biếc", "uese, uế")
    fun `upstream modern-style cases`(keys: String, expected: String) =
        assertEquals(expected, telexRaw.compose(keys))
}
