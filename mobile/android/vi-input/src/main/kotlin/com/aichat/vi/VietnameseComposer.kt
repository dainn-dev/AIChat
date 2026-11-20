package com.aichat.vi

/**
 * Immutable snapshot of the text being composed (normally one word, between two word breaks).
 *
 * Create new states only through [VietnameseComposer]; start from [ComposerState.EMPTY].
 */
public class ComposerState internal constructor(
    internal val composition: List<Transformation>,
    /** Text to show as the composing (underlined) region. */
    public val composingText: String,
    /** The keys exactly as typed, e.g. `nguowif` for "người". */
    public val rawText: String,
) {
    public val isEmpty: Boolean get() = composition.isEmpty()

    override fun toString(): String = "ComposerState(composing=$composingText, raw=$rawText)"

    public companion object {
        @JvmField
        public val EMPTY: ComposerState = ComposerState(emptyList(), "", "")
    }
}

/** Result of [VietnameseComposer.type]: text to commit to the editor now, and the next state. */
public data class KeyOutcome(val committed: String, val state: ComposerState)

/**
 * Vietnamese input composer (Telex / VNI), a reducer over immutable [ComposerState]s:
 *
 * ```
 * val composer = VietnameseComposer(InputMethod.TELEX)
 * val state = composer.process(ComposerState.EMPTY, "nguowif")
 * state.composingText          // "người"
 * composer.commitText(state)   // "người"
 * ```
 *
 * @param autoRestoreEnglish spell-check fallback. When on, a word that is not a valid Vietnamese
 *   syllable is shown and committed as the raw keys (`class`, `street`, `window` stay English), and
 *   once the word is invalid no further key is transformed. When off, the output is exactly what
 *   the Bamboo engine produces.
 */
public class VietnameseComposer(
    public val inputMethod: InputMethod,
    public val toneStyle: ToneStyle = ToneStyle.MODERN,
    public val autoRestoreEnglish: Boolean = true,
) {
    private val rules = InputMethodRules.of(inputMethod)
    private val engine = BambooEngine(rules, classicToneStyle = toneStyle == ToneStyle.CLASSIC)

    /** True if [key] is part of a word for this input method (letters, Vietnamese letters, effect keys). */
    public fun isWordKey(key: Char): Boolean = engine.canProcessKey(key)

    /** True if [key] ends the current word (space, punctuation, digits in Telex, ...). */
    public fun isWordBreak(key: Char): Boolean = !isWordKey(key)

    /** Applies one keystroke. Non-word keys are appended literally. */
    public fun process(state: ComposerState, key: Char): ComposerState {
        val composition = state.composition
        val (_, word) = engine.splitLastWord(composition, rules.effectKeys)
        val locked = autoRestoreEnglish && isWordKey(key) && word.isNotEmpty() &&
            !engine.isValid(word, complete = false)
        val next = if (locked) engine.appendRaw(composition, key) else engine.processKey(composition, key)
        return stateOf(next)
    }

    /** Applies every char of [keys] in order. */
    public fun process(state: ComposerState, keys: CharSequence): ComposerState = keys.fold(state, ::process)

    /** Deletes the last letter and every tone/mark applied to it (`loạn` -> `lọa`... -> re-toned `lạo`). */
    public fun backspace(state: ComposerState): ComposerState = stateOf(engine.removeLastChar(state.composition))

    /** Final text for the state, with the spell-check fallback applied to complete words. */
    public fun commitText(state: ComposerState): String = render(state.composition, complete = true)

    /**
     * IME helper: a word-break key commits the current word followed by the key and resets the state;
     * any other key is processed into the composing word.
     */
    public fun type(state: ComposerState, key: Char): KeyOutcome =
        if (isWordBreak(key)) {
            KeyOutcome(commitText(state) + key, ComposerState.EMPTY)
        } else {
            KeyOutcome("", process(state, key))
        }

    private fun stateOf(composition: List<Transformation>): ComposerState =
        if (composition.isEmpty()) {
            ComposerState.EMPTY
        } else {
            ComposerState(composition, render(composition, complete = false), flatten(composition, FlattenMode.ENGLISH))
        }

    private fun render(composition: List<Transformation>, complete: Boolean): String {
        val out = StringBuilder()
        var wordStart = 0
        composition.forEachIndexed { i, t ->
            if (engine.isWordBoundary(t, rules.effectKeys)) {
                out.append(renderWord(composition.subList(wordStart, i), complete))
                out.append(flatten(listOf(t)))
                wordStart = i + 1
            }
        }
        out.append(renderWord(composition.subList(wordStart, composition.size), complete))
        return out.toString()
    }

    private fun renderWord(word: List<Transformation>, complete: Boolean): String {
        val vietnamese = flatten(word)
        val keepVietnamese = !autoRestoreEnglish ||
            word.isEmpty() ||
            engine.isValid(word, complete) ||
            // Nothing Vietnamese happened (escaped keys like `ass` -> as): keep what the engine shows.
            !ViChars.hasAnyVietnameseRune(vietnamese) ||
            // Consonant-only chat abbreviations: đc, đt, ng.
            !ViChars.hasAnyVowel(vietnamese)
        return if (keepVietnamese) vietnamese else flatten(word, FlattenMode.ENGLISH)
    }
}
