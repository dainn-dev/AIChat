/*
 * Ported from Bamboo (https://github.com/BambooEngine/bamboo-core, rules_parser.go and
 * input_method_def.go), commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
 * Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
 *
 * Bamboo parses a string DSL ("UOA_ƯƠĂ__Ư") at runtime; here the same rules are built
 * directly with typed helpers.
 */
package com.aichat.vi

/** Keyboard typing scheme. */
public enum class InputMethod {
    /** Telex: s f r x j tones, z clears, aa ee oo aw ow uw dd marks, a lone `w` types `ư`. */
    TELEX,

    /** VNI: 1-5 tones, 0 clears, 6 (â ê ô), 7 (ư ơ), 8 (ă), 9 (đ). */
    VNI,
}

/** Where the tone goes on `oa`, `oe`, `uy` (open syllables). */
public enum class ToneStyle {
    /** New style, tone on the main vowel: hoà, khoẻ, thuỷ. */
    MODERN,

    /** Old style, tone on the first vowel: hòa, khỏe, thủy. */
    CLASSIC,
}

internal enum class EffectType { APPENDING, MARK, TONE }

/** Key used by transformations the engine adds by itself; they never show up in the raw keystrokes. */
internal const val VIRTUAL_KEY: Char = '\u0000'

/**
 * One rule of an input method. [effect] holds a [Tone] ordinal for TONE rules and a [Mark] ordinal
 * for MARK rules. [effectOn] is the char the rule applies to and [result] what it produces.
 */
internal data class Rule(
    val key: Char,
    val effectType: EffectType,
    val effect: Int = 0,
    val effectOn: Char = key,
    val result: Char = key,
) {
    val tone: Tone get() = Tone.entries[effect]
    val mark: Mark get() = Mark.entries[effect]
}

/** The compiled rule set of an [InputMethod]. */
internal class InputMethodRules(val rules: List<Rule>) {
    private val byKey: Map<Char, List<Rule>> = rules.groupBy { it.key }

    /** Keys that carry an effect (tone/mark/appending) in this method. */
    val effectKeys: Set<Char> = byKey.keys

    /** Key that turns `uo` into `ươ` (Telex `w`, VNI `7`); drives the `uơn` -> `ươn` shortcut. */
    val superKey: Char? = rules.firstOrNull { it.effectType == EffectType.MARK && it.result == 'ư' }?.key

    fun rulesFor(key: Char): List<Rule> = byKey[key].orEmpty()

    companion object {
        private val cache: Map<InputMethod, InputMethodRules> = mapOf(
            InputMethod.TELEX to InputMethodRules(telexRules()),
            InputMethod.VNI to InputMethodRules(vniRules()),
        )

        fun of(method: InputMethod): InputMethodRules = cache.getValue(method)
    }
}

private fun toneRule(key: Char, tone: Tone) = Rule(key, EffectType.TONE, tone.ordinal)

private fun appendRule(key: Char, char: Char) = Rule(key, EffectType.APPENDING, 0, char, char)

/**
 * Rules for [key] turning [from] into [to] (e.g. 'o' -> 'ô'), port of Bamboo's ParseToneLessRule:
 * every other member of the mark family (with every tone) converts to [to], and [to] itself gets an
 * "undo" rule (effect = no mark) back to [from].
 */
private fun markRules(key: Char, from: Char, to: Char): List<Rule> {
    val mark = requireNotNull(ViChars.markOf(to)) { "No mark family for $to" }
    return ViChars.markFamily(from).flatMap { member ->
        when {
            member == to -> listOf(Rule(key, EffectType.MARK, Mark.NONE.ordinal, to, from))
            ViChars.isVowel(member) -> Tone.entries.map { tone ->
                Rule(key, EffectType.MARK, mark.ordinal, ViChars.withTone(member, tone), ViChars.withTone(to, tone))
            }
            else -> listOf(Rule(key, EffectType.MARK, mark.ordinal, member, to))
        }
    }
}

private fun markRules(key: Char, vararg pairs: Pair<Char, Char>): List<Rule> =
    pairs.flatMap { (from, to) -> markRules(key, from, to) }

private fun commonToneRules(clear: Char, acute: Char, grave: Char, hook: Char, tilde: Char, dot: Char) = listOf(
    toneRule(clear, Tone.NONE),
    toneRule(acute, Tone.ACUTE),
    toneRule(grave, Tone.GRAVE),
    toneRule(hook, Tone.HOOK),
    toneRule(tilde, Tone.TILDE),
    toneRule(dot, Tone.DOT),
)

/** Bamboo's "Telex W": standard Telex plus a lone `w` typing `ư`. */
private fun telexRules(): List<Rule> =
    commonToneRules(clear = 'z', acute = 's', grave = 'f', hook = 'r', tilde = 'x', dot = 'j') +
        markRules('a', 'a' to 'â') +
        markRules('e', 'e' to 'ê') +
        markRules('o', 'o' to 'ô') +
        markRules('w', 'u' to 'ư', 'o' to 'ơ', 'a' to 'ă') + appendRule('w', 'ư') +
        markRules('d', 'd' to 'đ')

private fun vniRules(): List<Rule> =
    commonToneRules(clear = '0', acute = '1', grave = '2', hook = '3', tilde = '4', dot = '5') +
        markRules('6', 'a' to 'â', 'e' to 'ê', 'o' to 'ô') +
        markRules('7', 'u' to 'ư', 'o' to 'ơ') +
        markRules('8', 'a' to 'ă') +
        markRules('9', 'd' to 'đ')
