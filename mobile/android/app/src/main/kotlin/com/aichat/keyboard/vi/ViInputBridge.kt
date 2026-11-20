/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.AbstractEditorInstance
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.ime.keyboard.KeyboardMode
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Android glue between FlorisBoard's `EditorInstance` and [ViInputController]. `EditorInstance` calls
 * one method of this class at each hook point (marked `AIChat MODIFIED`); everything else stays here.
 */
class ViInputBridge(context: Context, editor: AbstractEditorInstance) {
    private val prefs by FlorisPreferenceStore
    private val keyboardManager by context.keyboardManager()
    private val subtypeManager by context.subtypeManager()
    private val port = EditorInstanceViPort(editor)
    private val scope = MainScope()

    val controller = ViInputController(settings = ::readSettings)

    init {
        // Leaving the Vietnamese subtype (globe key, subtype picker) ends the word right away, so a word
        // shown as `uẻ` becomes `user` instead of staying half-composed.
        scope.launch {
            subtypeManager.activeSubtypeFlow
                .distinctUntilChangedBy { it.composer }
                .collect { if (!isActive()) finishWord() }
        }
    }

    /**
     * True when the active subtype uses vi-input, the letters layer is shown (so VNI tone digits only
     * come from the number row / key hints, never from the symbols layer) and the field is not a
     * password field.
     */
    fun isActive(): Boolean {
        val composer = keyboardManager.resources.composers.value?.get(subtypeManager.activeSubtype.composer)
        val state = keyboardManager.activeState
        return composer is ViInputComposer &&
            state.keyboardMode == KeyboardMode.CHARACTERS &&
            state.keyVariation != KeyVariation.PASSWORD
    }

    /** Swaps the vi-input marker composer for the stateful adapter. */
    fun resolveComposer(composer: Composer): Composer {
        if (composer !is ViInputComposer) return composer
        return if (isActive()) controller.composerAdapter else composer
    }

    /**
     * Called before `commitChar`. Ends the word when the key will not reach the engine: vi-input is
     * not active, there is no cursor (selection, unknown editor state), or the key is several code
     * points (upstream bypasses the composer for multi-grapheme text, and a combining sequence is
     * easier to commit after the word).
     */
    fun beforeCommitChar(text: String) {
        val bypassesEngine = !isActive() ||
            port.cursorPosition() == ViInputController.NO_CURSOR ||
            text.codePointCount(0, text.length) > 1
        if (bypassesEngine) finishWord()
    }

    fun afterCommitChar() = controller.onEditApplied(port.cursorPosition())

    /** Backspace through the engine. Returns false if the caller should delete normally. */
    fun backspace(): Boolean = isActive() && controller.onBackspace(port)

    fun finishWord() = controller.finishWord(port)

    fun reset() = controller.reset()

    fun onSelectionUpdate(oldSelection: EditorRange, newSelection: EditorRange) {
        controller.onSelectionUpdate(oldSelection.start, oldSelection.end, newSelection.start, newSelection.end)
    }

    private fun readSettings() = ViInputSettings(
        inputMethod = prefs.aichatVietnamese.inputMethod.get(),
        toneStyle = prefs.aichatVietnamese.toneStyle.get(),
        autoRestoreEnglish = prefs.aichatVietnamese.autoRestoreEnglish.get(),
    )
}

/** [ViEditorPort] backed by FlorisBoard's editor content tracking. */
internal class EditorInstanceViPort(private val editor: AbstractEditorInstance) : ViEditorPort {
    override fun textBeforeCursor(): CharSequence? {
        val content = editor.activeContent
        return if (content.selection.isCursorMode) content.textBeforeSelection else null
    }

    override fun cursorPosition(): Int {
        val selection = editor.activeContent.selection
        return if (selection.isCursorMode) selection.start else ViInputController.NO_CURSOR
    }

    override fun replaceBeforeCursor(deleteCount: Int, text: String): Boolean =
        editor.replaceTextBeforeCursor(deleteCount, text)
}
