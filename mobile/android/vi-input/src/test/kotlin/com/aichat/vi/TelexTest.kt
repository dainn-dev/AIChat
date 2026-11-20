package com.aichat.vi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class TelexTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "as, á", "af, à", "ar, ả", "ax, ã", "aj, ạ",
        "bas, bá", "maf, mà", "tor, tỏ", "nux, nũ", "tij, tị",
        "mootj, một", "ddaau, đâu", "tieengs, tiếng", "vieetj, việt", "chuaarn, chuẩn",
        "giamaf, giầm", "ddafi, đài", "khoong, không", "cuxng, cũng", "ddeemf, đềm",
    )
    fun `tones and words`(keys: String, expected: String) = assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "aa, â", "aw, ă", "ee, ê", "oo, ô", "ow, ơ", "uw, ư", "dd, đ", "w, ư",
        "caan, cân", "nawm, năm", "ddeen, đên", "bow, bơ", "tuw, tư", "ddi, đi", "wa, ưa",
        "nhuw, như", "toowi, tơi",
    )
    fun `vowel and consonant modifiers`(keys: String, expected: String) =
        assertEquals(expected, telex.compose(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "nguowif, người", "huowng, hương", "dduowngf, đường", "nuwowcs, nước", "muwowif, mười",
        "uwow, ươ", "wowfi, ười", "duwoi, dươi", "cuongw, cương", "nguoiw, ngươi", "thuow, thuơ",
        "buwoo, buô", "buowco, buôc", "cuoiwo, cuôi", "ruwowuj, rượu",
    )
    fun `uo horn handling`(keys: String, expected: String) = assertEquals(expected, telex.compose(keys))

    @ParameterizedTest(name = "{0} -> modern {1} / classic {2}")
    @CsvSource(
        "hoaf, hoà, hòa",
        "oaf, oà, òa",
        "khoer, khoẻ, khỏe",
        "thuyr, thuỷ, thủy",
        "huyf, huỳ, hùy",
        "quoocs, quốc, quốc",
        "giuwx, giữ, giữ",
        "gifn, gìn, gìn",
        "nghieengx, nghiễng, nghiễng",
        "khuya, khuya, khuya",
        "toans, toán, toán",
        "hoangf, hoàng, hoàng",
        "thuees, thuế, thuế",
        "muaf, mùa, mùa",
        "cuar, của, của",
        "kias, kía, kía",
        "quys, quý, quý",
        "chuyeenr, chuyển, chuyển",
        "khuyeets, khuyết, khuyết",
        "nguowif, người, người",
        "thuowr, thuở, thuở",
        "tooij, tội, tội",
        "hoaij, hoại, hoại",
        "ngoaif, ngoài, ngoài",
        "luaj, lụa, lụa",
        "giaf, già, già",
        "quaf, quà, quà",
        "tuyeetj, tuyệt, tuyệt",
    )
    fun `tone placement for both styles`(keys: String, modern: String, classic: String) {
        assertEquals(modern, telex.commit(keys), "modern")
        assertEquals(classic, telexClassic.commit(keys), "classic")
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "chuyrene, chuyển", "hosa, hoá", "tosan, toán", "gisa, giá", "thuyr, thuỷ", "huosong, huống",
        "nguowfi, người", "toosi, tối", "dduowfng, đường", "vieejt, việt",
    )
    fun `tone moves when the syllable changes`(keys: String, expected: String) =
        assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "hoaf, hoà", "nguoiwf, người", "duongwf, dường", "tieengs, tiếng", "tieesng, tiếng",
        "vietej, việt", "dduongwf, đường", "truongwf, trường",
    )
    fun `tone key at the end of the word`(keys: String, expected: String) =
        assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "ass, as", "aff, af", "arr, ar", "axx, ax", "ajj, aj", "ddd, dd", "aaa, aa", "eee, ee",
        "ooo, oo", "aww, aw", "ww, w", "reff, ref", "toorr, toorr", "tooss, tooss",
    )
    fun `double key undoes the effect`(keys: String, expected: String) =
        assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("asz, a", "losz, lo", "chuyeenrz, chuyên", "toosz, tô", "afz, a", "nguowifz, ngươi")
    fun `z removes the tone`(keys: String, expected: String) = assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "Vieetj, Việt", "VIEETJ, VIỆT", "DDuowngf, Đường", "Nguowif, Người", "NGUOIWF, NGƯỜI",
        "DDi, Đi", "Ddi, Đi", "SDD, SDD", "AW, Ă", "W, Ư", "HoaF, Hoà", "CLASS, CLASS",
    )
    fun `case is preserved`(keys: String, expected: String) = assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} stays {0}")
    @CsvSource(
        "class", "street", "awesome", "window", "hello", "google", "facebook", "windows", "string", "Kotlin",
        "android", "wifi", "please", "github", "Street", "keyboard", "cloud", "phone", "free", "where",
    )
    fun `english words stay english`(word: String) = assertEquals(word, telex.commit(word))

    /** English words that are also valid Telex for a Vietnamese syllable: same trade-off as Bamboo. */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "test, tét", "was, ứa", "wow, ươ", "mix, mĩ", "box, bõ", "bus, bú", "dodo, đô",
        "user, user", "fix, fix", "khongbiet, khongbiet",
    )
    fun `known ambiguities with english`(keys: String, expected: String) = assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource("ddc, đc", "ddt, đt", "kh, kh", "ng, ng", "ko, ko", "dd, đ")
    fun `consonant-only abbreviations are kept`(keys: String, expected: String) =
        assertEquals(expected, telex.commit(keys))

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "loanj⌫, loạ", "hanhj⌫, hạn", "thuow⌫, thu", "vieetj⌫t, việt", "turyn⌫⌫, tu", "as⌫, ''",
        "nguowif⌫, ngườ", "dd⌫, ''", "chuyeenr⌫, chuyể", "reff⌫f, rè", "abc⌫⌫⌫, ''", "⌫a, a",
        "tieengs⌫, tiến", "hoaf⌫, ho", "hoaf⌫⌫, h",
    )
    fun `backspace removes a letter and its effects`(script: String, expected: String) =
        assertEquals(expected, telex.compose(script))
}
