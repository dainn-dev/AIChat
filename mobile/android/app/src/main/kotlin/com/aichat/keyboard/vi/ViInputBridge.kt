/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import android.content.Context
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.editor.AbstractEditorInstance
import dev.patrickgold.florisboard.ime.editor.EditorRange
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Android glue between FlorisBoard's `EditorInstance` and [ViInputController]. `EditorInstance` calls
 * one method of this class at each hook point (marked `AIChat MODIFIED`); everything else stays here.
 *
 * Construction touches nothing: `EditorInstance` is built inside FlorisApplication's lazy singletons,
 * and reaching `keyboardManager`/`subtypeManager` from there would re-enter those lazies. The managers
 * and preferences are only read from [onStartInputView] on, when the IME is fully up.
 */
class ViInputBridge(private val context: Context, editor: AbstractEditorInstance) {
    private val port = EditorInstanceViPort(editor)

    @Volatile
    private var settings = ViInputSettings.DEFAULT
    private var scope: CoroutineScope? = null

    val controller = ViInputController(settings = { settings })

    /** The live Vietnamese settings, as the composer reads them on every key (glide uses the tone style). */
    fun currentSettings(): ViInputSettings = settings

    /**
     * Starts watching the settings and the active subtype, once per input session. Runs on the
     * non-immediate main dispatcher, so collectors never run inside the caller's stack.
     */
    fun onStartInputView() {
        controller.reset()
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        val prefs by FlorisPreferenceStore
        val vi = prefs.aichatVietnamese
        settings = ViInputSettings(vi.inputMethod.get(), vi.toneStyle.get(), vi.autoRestoreEnglish.get())
        newScope.launch {
            combine(vi.inputMethod.asFlow(), vi.toneStyle.asFlow(), vi.autoRestoreEnglish.asFlow()) { method, tone, restore ->
                ViInputSettings(method, tone, restore)
            }.collect { settings = it }
        }
        // Leaving the Vietnamese subtype (globe key, subtype picker) ends the word right away, so a word
        // shown as `uẻ` becomes `user` instead of staying half-composed.
        newScope.launch {
            context.subtypeManager().value.activeSubtypeFlow
                .distinctUntilChangedBy { it.composer }
                .collect { if (!isActive()) finishWord() }
        }
    }

    /** Stops the collectors; called when the input session ends. */
    fun close() {
        controller.reset()
        scope?.cancel()
        scope = null
    }

    /** True when the active subtype uses vi-input and the field / layer accepts composed text. */
    fun isActive(): Boolean {
        val keyboardManager = context.keyboardManager().value
        val name = context.subtypeManager().value.activeSubtype.composer
        val attributes = port.editor.activeInfo.inputAttributes
        return ViInputGate.isEligible(
            isViComposer = ViInputComposer.isViInput(name, keyboardManager.resources.composers.value?.get(name)),
            keyboardMode = keyboardManager.activeState.keyboardMode,
            type = attributes.type,
            variation = attributes.variation,
        )
    }

    /** Swaps the vi-input marker composer (or the legacy `telex` name) for the stateful adapter. */
    fun resolveComposer(name: ExtensionComponentName, composer: Composer): Composer {
        if (!ViInputComposer.isViInput(name, composer)) return composer
        return if (isActive()) controller.composerAdapter else composer
    }

    fun beforeCommitChar(text: String) = controller.beforeKey(port, text, engineActive = isActive())

    fun afterCommitChar() = controller.onEditApplied(port.cursorPosition())

    /** Backspace through the engine. Returns false if the caller should delete normally. */
    fun backspace(): Boolean = isActive() && controller.onBackspace(port)

    fun finishWord() = controller.finishWord(port)

    fun reset() = controller.reset()

    fun onSelectionUpdate(oldSelection: EditorRange, newSelection: EditorRange, isExpectedByEditor: Boolean) {
        controller.onSelectionUpdate(
            oldSelection.start, oldSelection.end, newSelection.start, newSelection.end, isExpectedByEditor,
        )
    }
}

/** [ViEditorPort] backed by FlorisBoard's editor content tracking. */
internal class EditorInstanceViPort(val editor: AbstractEditorInstance) : ViEditorPort {
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
