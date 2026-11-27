/*
 * Copyright (C) 2026 AIChat
 */
package com.aichat.vi

import java.text.Normalizer

/** Text helpers for matching plain-letter input against accented Vietnamese syllables. */
public object ViText {
    /**
     * Lowercases [word], normalizes it to NFC and removes every tone and mark (`được` -> `duoc`,
     * `đ` -> `d`). Characters that are not Vietnamese letters are kept as they are.
     */
    public fun fold(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC).lowercase().map(ViChars::base).joinToString("")

    /**
     * Moves the tone mark of [syllable] to where [style] puts it (`hòa` <-> `hoà`), keeping case
     * and every other letter. Anything that is not a valid syllable is returned unchanged.
     */
    public fun withToneStyle(syllable: String, style: ToneStyle): String {
        val word = Normalizer.normalize(syllable, Normalizer.Form.NFC)
        if (!ViSyllable.isValid(word)) return syllable
        val parts = ViSyllable.split(word)
        val tone = parts.vowel.map { ViChars.toneOf(it.lowercaseChar()) }.firstOrNull { it != Tone.NONE }
            ?: return syllable
        val nucleus = parts.vowel.lowercase().map { ViChars.withTone(it, Tone.NONE) }.joinToString("")
        val target = TonePlacement.targetIndex(nucleus, parts.final.isNotEmpty(), style == ToneStyle.CLASSIC)
            ?: return syllable
        val vowelStart = parts.initial.length
        return buildString(word.length) {
            word.forEachIndexed { i, c ->
                val inVowel = i >= vowelStart && i < vowelStart + parts.vowel.length
                append(
                    when {
                        !inVowel -> c
                        i - vowelStart == target -> retone(c, tone)
                        else -> retone(c, Tone.NONE)
                    },
                )
            }
        }
    }

    private fun retone(c: Char, tone: Tone): Char {
        val toned = ViChars.withTone(c.lowercaseChar(), tone)
        return if (c.isUpperCase()) toned.uppercaseChar() else toned
    }
}
