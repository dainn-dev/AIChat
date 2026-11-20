/*
 * Ported from Bamboo (https://github.com/BambooEngine/bamboo-core, bamboo.go and
 * bamboo_utils.go), commit b2e49a2b48c7d3772a3673142a7747eccd9d5f79.
 * Copyright (C) 2018 Luong Thanh Lam <ltlam93@gmail.com>. MIT License, see NOTICE.
 *
 * Differences from upstream:
 *  - compositions are immutable lists; every operation returns a new list instead of mutating
 *    engine state (Bamboo moves a tone by mutating the transformation's target in place);
 *  - free tone marking is always on (no "tone must follow the vowel" mode);
 *  - when the tone has to move, every tone transformation of the word moves with it, so a
 *    replaced tone (`as` then `f`) can never be left behind on the old vowel.
 */
package com.aichat.vi

private typealias Composition = List<Transformation>

/** The Bamboo transformation algorithm over immutable compositions. */
internal class BambooEngine(private val im: InputMethodRules, private val classicToneStyle: Boolean) {

    private data class Target(val target: Transformation, val rule: Rule)

    fun canProcessKey(key: Char): Boolean {
        val lower = key.lowercaseChar()
        return when {
            ViChars.isAsciiAlpha(lower) || lower in im.effectKeys -> true
            ViChars.isWordBreakSymbol(lower) -> false
            else -> ViChars.isVietnameseRune(lower)
        }
    }

    /** Appends [key] to [composition], applying whatever Vietnamese transformation it triggers. */
    fun processKey(composition: Composition, key: Char): Composition {
        val lower = key.lowercaseChar()
        val upper = key.isUpperCase()
        if (!canProcessKey(lower)) return composition + Transformation.appending(lower, upper)
        val (previous, syllable) = splitLastSyllable(composition)
        return previous + generate(syllable, lower, upper)
    }

    /** Appends [key] literally, without any transformation. */
    fun appendRaw(composition: Composition, key: Char): Composition =
        composition + Transformation.appending(key.lowercaseChar(), key.isUpperCase())

    /** Removes the last letter together with every mark/tone applied to it (Bamboo RemoveLastChar). */
    fun removeLastChar(composition: Composition): Composition {
        val last = composition.lastOrNull { it.isAppending } ?: return composition
        if (!canProcessKey(last.rule.key)) return composition.filter { it !== last }
        val (previous, word) = splitLastWord(composition, im.effectKeys)
        val remaining = word.filter { it !== last && it.target !== last }
        return previous + refreshToneTarget(remaining)
    }

    // ---------------------------------------------------------------- word / syllable splitting

    /** Splits off the trailing word: everything after the last non-word char (Bamboo extractLastWord). */
    fun splitLastWord(composition: Composition, effectKeys: Set<Char>): Pair<Composition, Composition> {
        val boundary = composition.indexOfLast { isWordBoundary(it, effectKeys) }
        return composition.subList(0, boundary + 1) to composition.subList(boundary + 1, composition.size)
    }

    fun isWordBoundary(t: Transformation, effectKeys: Set<Char>): Boolean {
        if (!t.isAppending || t.rule.key == VIRTUAL_KEY) return false
        val c = ViChars.base(t.rule.effectOn.lowercaseChar())
        return !ViChars.isAsciiAlpha(c) && c !in effectKeys
    }

    /** The trailing syllable of the last word: Bamboo restarts a syllable where spelling breaks. */
    private fun splitLastSyllable(composition: Composition): Pair<Composition, Composition> {
        val (previous, word) = splitLastWord(composition, emptySet())
        var anchor = 0
        for (i in word.indices) {
            if (!isValid(word.subList(anchor, i + 1), complete = false)) anchor = i
        }
        return (previous + word.subList(0, anchor)) to word.subList(anchor, word.size)
    }

    // ---------------------------------------------------------------- spelling

    fun isValid(composition: Composition, complete: Boolean): Boolean {
        if (composition.size <= 1) return true
        val lastTone = composition.lastOrNull { it.rule.effectType == EffectType.TONE }
        if (lastTone != null && !hasValidTone(composition, lastTone.rule.tone)) return false
        val (fc, vo, lc) = splitCvc(composition)
        val mode = FlattenMode.VI_TONELESS_LOWER
        return ViSyllable.isValidCvc(flatten(fc, mode), flatten(vo, mode), flatten(lc, mode), complete)
    }

    private fun hasValidTone(composition: Composition, tone: Tone): Boolean {
        val (_, _, lc) = splitCvc(composition)
        if (lc.isEmpty()) return true
        return ViSyllable.toneFitsFinal(tone, flatten(lc, FlattenMode.ENGLISH_LOWER))
    }

    /** CVC split where each group also carries the marks/tones that target its letters. */
    private fun splitCvc(composition: Composition): Triple<Composition, Composition, Composition> {
        val letters = composition.filter { it.target == null }
        val effects = composition.filter { it.target != null }.groupBy { it.target }
        val (fc, vo, lc) = splitCvc(letters, { ViChars.isVowel(it.rule.result) }, { it.rule.result })
        fun withEffects(group: Composition) = group + group.flatMap { effects[it].orEmpty() }
        return Triple(withEffects(fc), withEffects(vo), withEffects(lc))
    }

    private fun rightmostVowels(composition: Composition): Composition = splitCvc(composition).second

    // ---------------------------------------------------------------- tone placement

    private fun findToneTarget(composition: Composition): Transformation? {
        if (composition.isEmpty()) return null
        val (_, vo, lc) = splitCvc(composition)
        val vowels = vo.filter { it.isAppending }
        return when (vowels.size) {
            1 -> vowels[0]
            2 -> if (classicToneStyle) classicTwoVowelTarget(vo, vowels, lc) else modernTwoVowelTarget(vowels, lc)
            3 -> if (flatten(vowels, FlattenMode.ENGLISH_BASE_LOWER) == "uye") vowels[2] else vowels[1]
            else -> null
        }
    }

    private fun classicTwoVowelTarget(vo: Composition, vowels: Composition, lc: Composition): Transformation {
        val horned = vo.lastOrNull { it.rule.result == 'ơ' || it.rule.result == 'ê' }
        if (horned != null) return horned.target ?: horned
        return if (lc.isNotEmpty()) vowels[1] else vowels[0]
    }

    private fun modernTwoVowelTarget(vowels: Composition, lc: Composition): Transformation {
        if (lc.isNotEmpty()) return vowels[1]
        val pair = flatten(vowels, FlattenMode.ENGLISH_BASE_LOWER)
        return if (pair in MODERN_SECOND_VOWEL_PAIRS) vowels[1] else vowels[0]
    }

    /**
     * Moves the tone when the syllable changed shape (`chuyr` = chuỷ, then `chuyrene` = chuyển).
     * Only done while the syllable is still valid Vietnamese.
     */
    private fun refreshToneTarget(syllable: Composition): Composition {
        if (!isValid(syllable, complete = false)) return syllable
        val lastTone = syllable.lastOrNull { it.rule.effectType == EffectType.TONE && it.target != null }
            ?: return syllable
        if (rightmostVowels(syllable).isEmpty()) return syllable
        val newTarget = findToneTarget(syllable) ?: return syllable
        if (lastTone.target === newTarget) return syllable
        return syllable.map { t ->
            if (t.rule.effectType == EffectType.TONE && t.target != null) t.retarget(newTarget) else t
        }
    }

    // ---------------------------------------------------------------- target search

    private fun findTarget(composition: Composition, rules: List<Rule>): Target? {
        val current = flatten(composition)
        val toneRule = rules.firstOrNull { rule ->
            rule.effectType == EffectType.TONE &&
                flatten(composition + Transformation(rule, toneTargetFor(composition, rule))) != current
        }
        if (toneRule != null) {
            val target = toneTargetFor(composition, toneRule) ?: return null
            val clearsNothing = toneRule.tone == Tone.NONE &&
                isFree(composition, target, EffectType.TONE) &&
                ViChars.toneOf(target.rule.result) == Tone.NONE
            return if (clearsNothing) null else Target(target, toneRule)
        }
        return findMarkTarget(composition, rules)
    }

    private fun toneTargetFor(composition: Composition, rule: Rule): Transformation? =
        if (hasValidTone(composition, rule.tone)) findToneTarget(composition) else null

    private fun findMarkTarget(composition: Composition, rules: List<Rule>): Target? {
        val current = flatten(composition)
        for (trans in composition.asReversed()) {
            for (rule in rules) {
                if (rule.effectType != EffectType.MARK || trans.rule.result != rule.effectOn || rule.effect == 0) continue
                val target = rootTarget(trans)
                val candidate = composition + Transformation(rule, target)
                if (flatten(candidate) == current) continue
                if (isValid(candidate, complete = false)) return Target(target, rule)
            }
        }
        return null
    }

    private fun isFree(composition: Composition, trans: Transformation, type: EffectType): Boolean =
        composition.none { it.target === trans && it.rule.effectType == type }

    // ---------------------------------------------------------------- transformation generation

    /** Returns [syllable] with the effects of typing [lowerKey] appended (Bamboo generateTransformations). */
    private fun generate(syllable: Composition, lowerKey: Char, upper: Boolean): Composition {
        val rules = im.rulesFor(lowerKey)
        var added = generateEffects(syllable, rules, lowerKey, upper)
        if (added.isEmpty()) {
            // No rule applies: the key becomes a letter (possibly a rule-defined one, Telex w = ư).
            added = listOf(appendingFor(rules, lowerKey, upper))
            uowShortcut(syllable + added)?.let { added = added + it }
        }
        return refreshToneTarget(syllable + added)
    }

    private fun appendingFor(rules: List<Rule>, lowerKey: Char, upper: Boolean): Transformation {
        val rule = rules.firstOrNull { it.effectType == EffectType.APPENDING }
            ?: return Transformation.appending(lowerKey, upper)
        val char = rule.effectOn.lowercaseChar()
        return Transformation(rule.copy(effectOn = char, result = char), null, upper || rule.effectOn.isUpperCase())
    }

    /**
     * 1 | o + ff     ->  undo + append       -> of
     * 2 | o + fs     ->  override            -> ó
     * 3 | o + fz     ->  override            -> o
     * 4 | o + z      ->  append              -> oz
     * 5 | o + f      ->  tone_grave          -> ò
     * 6 | w + w      ->  raw                 -> w
     * 7 | (u)wo + w  ->  undo + append       -> uow
     */
    private fun generateEffects(composition: Composition, rules: List<Rule>, lowerKey: Char, upper: Boolean): Composition {
        // Typing a letter-producing key twice gives the key itself: w + w -> w.
        val last = composition.lastOrNull()
        if (last != null && last.isAppending && last.rule.key == lowerKey && last.rule.key != last.rule.result) {
            return listOf(Transformation.virtual(EffectType.MARK, Mark.RAW.ordinal, last))
        }
        val found = findTarget(composition, rules)
        if (found != null) return applyFound(composition, rules, found, upper)

        reshapeUhoToUo(composition, rules, upper)?.let { return it }

        // An effect key with nothing to apply undoes its previous effect and types itself: ươ + w -> uow.
        val undo = undoEffects(composition, rules)
        return if (undo.isEmpty()) emptyList() else undo + Transformation.appending(lowerKey, upper)
    }

    private fun applyFound(composition: Composition, rules: List<Rule>, found: Target, upper: Boolean): Composition {
        val applied = Transformation(found.rule, found.target, upper)
        if (found.rule.effectType != EffectType.MARK) return listOf(applied)
        val next = composition + applied
        if (isValid(next, complete = true)) return listOf(applied)
        // uo + w: horn the second vowel too (virtual), so `nguow` gives ngươ rather than nguơ.
        val second = findTarget(next, rules) ?: return listOf(applied)
        return listOf(applied, Transformation(second.rule.copy(key = VIRTUAL_KEY), second.target))
    }

    /** ươ/ưo(i/c/ng) + o -> uô: drop the horn on u and apply the key to o. */
    private fun reshapeUhoToUo(composition: Composition, rules: List<Rule>, upper: Boolean): Composition? {
        if (!UHO.containsMatchIn(flatten(composition, FlattenMode.VI_TONELESS_LOWER))) return null
        val firstVowel = rightmostVowels(composition).firstOrNull { it.isAppending } ?: return null
        val clearHorn = Transformation.virtual(EffectType.MARK, Mark.NONE.ordinal, firstVowel)
        val found = findTarget(composition + clearHorn, rules) ?: return null
        if (found.target === firstVowel) return null
        return listOf(clearHorn, Transformation(found.rule, found.target, upper))
    }

    private fun undoEffects(composition: Composition, rules: List<Rule>): Composition {
        val current = flatten(composition, FlattenMode.VI_TONELESS_LOWER)
        val undo = ArrayList<Transformation>()
        for (rule in rules) {
            when (rule.effectType) {
                EffectType.TONE -> toneTargetFor(composition, rule)?.let {
                    undo += Transformation.virtual(EffectType.TONE, Tone.NONE.ordinal, it)
                }
                EffectType.MARK -> for (trans in composition.asReversed()) {
                    if (trans.rule.result != rule.effectOn) continue
                    val clear = Transformation.virtual(EffectType.MARK, Mark.NONE.ordinal, rootTarget(trans))
                    if (flatten(composition + clear, FlattenMode.VI_TONELESS_LOWER) != current) undo += clear
                }
                EffectType.APPENDING -> Unit
            }
        }
        return undo
    }

    /** `uơ`/`ưo` followed by more letters gets the missing horn: huơn -> hươn. */
    private fun uowShortcut(syllable: Composition): Transformation? {
        val superKey = im.superKey ?: return null
        if (!UO_TAIL.containsMatchIn(flatten(syllable, FlattenMode.VI_TONELESS_LOWER))) return null
        val found = findTarget(syllable, im.rulesFor(superKey)) ?: return null
        return Transformation(found.rule.copy(key = VIRTUAL_KEY), found.target)
    }

    private companion object {
        val MODERN_SECOND_VOWEL_PAIRS = setOf("oa", "oe", "uy", "ue", "uo")
        val UO_TAIL = Regex("(uơ|ưo)\\p{L}+")
        val UHO = Regex("(ưo|ươ)")

        tailrec fun rootTarget(t: Transformation): Transformation {
            val target = t.target ?: return t
            return rootTarget(target)
        }
    }
}
