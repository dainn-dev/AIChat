/*
 * Ported from Bamboo (https://github.com/BambooEngine/bamboo-core, bamboo.go and flattener.go),
 * commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
 * Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
 */
package com.aichat.vi

/**
 * One step of a composition. APPENDING transformations add a letter; MARK/TONE transformations
 * point at the appended letter they modify through [target].
 *
 * Instances are immutable and compared by identity (like Bamboo's pointers): two appended `a`
 * letters are different transformations. To "move" a transformation, replace it with a copy.
 */
internal class Transformation(
    val rule: Rule,
    val target: Transformation? = null,
    val isUpperCase: Boolean = false,
) {
    val isAppending: Boolean get() = rule.effectType == EffectType.APPENDING

    fun retarget(newTarget: Transformation?): Transformation = Transformation(rule, newTarget, isUpperCase)

    override fun toString(): String = "T(${rule.effectType} key=${rule.key} on=${rule.effectOn} -> ${rule.result})"

    companion object {
        fun appending(key: Char, isUpperCase: Boolean): Transformation =
            Transformation(Rule(key, EffectType.APPENDING, 0, key, key), null, isUpperCase)

        fun virtual(effectType: EffectType, effect: Int, target: Transformation?): Transformation =
            Transformation(Rule(VIRTUAL_KEY, effectType, effect, VIRTUAL_KEY, VIRTUAL_KEY), target)
    }
}

/** How [flatten] renders a composition. */
internal data class FlattenMode(
    /** Render the keys as typed instead of the Vietnamese result. */
    val english: Boolean = false,
    val toneLess: Boolean = false,
    val markLess: Boolean = false,
    val lowerCase: Boolean = false,
) {
    companion object {
        val VIETNAMESE = FlattenMode()
        val ENGLISH = FlattenMode(english = true)
        val VI_TONELESS_LOWER = FlattenMode(toneLess = true, lowerCase = true)
        val VI_BASE_LOWER = FlattenMode(toneLess = true, markLess = true, lowerCase = true)
        val ENGLISH_BASE_LOWER = FlattenMode(english = true, toneLess = true, markLess = true, lowerCase = true)
        val ENGLISH_LOWER = FlattenMode(english = true, lowerCase = true)
    }
}

/** Renders a composition to text (port of Bamboo's Flatten/getCanvas). */
internal fun flatten(composition: List<Transformation>, mode: FlattenMode = FlattenMode.VIETNAMESE): String {
    val effects = HashMap<Transformation, MutableList<Transformation>>()
    val letters = ArrayList<Transformation>(composition.size)
    for (t in composition) {
        when {
            mode.english -> if (t.rule.key != VIRTUAL_KEY) letters += t
            t.isAppending -> if (t.rule.key != VIRTUAL_KEY) letters += t
            t.target != null -> effects.getOrPut(t.target) { ArrayList() } += t
        }
    }
    val out = StringBuilder(letters.size)
    for (letter in letters) {
        var c = if (mode.english) letter.rule.key else applyEffects(letter, effects[letter].orEmpty())
        if (mode.toneLess) c = ViChars.withTone(c, Tone.NONE)
        if (mode.markLess) c = ViChars.withMark(c, Mark.NONE)
        c = when {
            mode.lowerCase -> c.lowercaseChar()
            letter.isUpperCase -> c.uppercaseChar()
            else -> c
        }
        out.append(c)
    }
    return out.toString()
}

private fun applyEffects(letter: Transformation, effects: List<Transformation>): Char =
    effects.fold(letter.rule.effectOn) { c, t ->
        when (t.rule.effectType) {
            EffectType.MARK -> if (t.rule.mark == Mark.RAW) letter.rule.key else ViChars.withMark(c, t.rule.mark)
            EffectType.TONE -> ViChars.withTone(c, t.rule.tone)
            EffectType.APPENDING -> c
        }
    }
