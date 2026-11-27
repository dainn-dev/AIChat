/*
 * Tone placement rules ported from Bamboo (https://github.com/BambooEngine/bamboo-core,
 * bamboo_utils.go findToneTarget), commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
 * Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
 */
package com.aichat.vi

/** Which vowel of a syllable nucleus carries the tone mark. Shared by [BambooEngine] and [ViText]. */
internal object TonePlacement {
    private val MODERN_SECOND_VOWEL_PAIRS = setOf("oa", "oe", "uy", "ue", "uo")
    private val MARKED_SECOND_VOWEL_PAIRS = setOf("iê", "yê", "uô", "ươ")

    /**
     * Index into [nucleus] of the vowel that takes the tone, or null when there is none.
     * [nucleus] is the lowercase, toneless vowel run keeping its marks (`ươ`, `oa`, `uyê`);
     * [hasFinal] says whether a final consonant follows; [classic] selects the old style.
     */
    fun targetIndex(nucleus: String, hasFinal: Boolean, classic: Boolean): Int? = when (nucleus.length) {
        1 -> 0
        2 -> when {
            // iê/yê/uô/ươ carry the tone on the marked second vowel in both styles, even while
            // the syllable is still open (`tiế` before its final `ng`).
            !hasFinal && nucleus in MARKED_SECOND_VOWEL_PAIRS -> 1
            classic -> classicTwoVowelIndex(nucleus, hasFinal)
            else -> modernTwoVowelIndex(nucleus, hasFinal)
        }
        3 -> if (nucleus.map(ViChars::base).joinToString("") == "uye") 2 else 1
        else -> null
    }

    private fun classicTwoVowelIndex(nucleus: String, hasFinal: Boolean): Int {
        val horned = nucleus.indexOfLast { it == 'ơ' || it == 'ê' }
        if (horned >= 0) return horned
        return if (hasFinal) 1 else 0
    }

    private fun modernTwoVowelIndex(nucleus: String, hasFinal: Boolean): Int {
        if (hasFinal) return 1
        val pair = nucleus.map(ViChars::base).joinToString("")
        return if (pair in MODERN_SECOND_VOWEL_PAIRS) 1 else 0
    }
}
