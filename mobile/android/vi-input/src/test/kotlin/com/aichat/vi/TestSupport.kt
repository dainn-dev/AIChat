package com.aichat.vi

/** Backspace marker usable inside key scripts: "loanj⌫" = type `loanj`, then press backspace. */
const val BACKSPACE: Char = '⌫'

fun VietnameseComposer.run(script: String): ComposerState =
    script.fold(ComposerState.EMPTY) { state, key ->
        if (key == BACKSPACE) backspace(state) else process(state, key)
    }

/** Composing text after typing [script]. */
fun VietnameseComposer.compose(script: String): String = run(script).composingText

/** Committed text after typing [script] and ending the word. */
fun VietnameseComposer.commit(script: String): String = commitText(run(script))

/** Full IME simulation through [VietnameseComposer.type]: returns everything committed plus the pending word. */
fun VietnameseComposer.typeAll(text: String): String {
    val out = StringBuilder()
    var state = ComposerState.EMPTY
    for (key in text) {
        val outcome = type(state, key)
        out.append(outcome.committed)
        state = outcome.state
    }
    return out.append(commitText(state)).toString()
}

val telex: VietnameseComposer = VietnameseComposer(InputMethod.TELEX)
val telexClassic: VietnameseComposer = VietnameseComposer(InputMethod.TELEX, ToneStyle.CLASSIC)
val telexRaw: VietnameseComposer = VietnameseComposer(InputMethod.TELEX, autoRestoreEnglish = false)
val telexClassicRaw: VietnameseComposer =
    VietnameseComposer(InputMethod.TELEX, ToneStyle.CLASSIC, autoRestoreEnglish = false)
val vni: VietnameseComposer = VietnameseComposer(InputMethod.VNI)
val vniClassic: VietnameseComposer = VietnameseComposer(InputMethod.VNI, ToneStyle.CLASSIC)
