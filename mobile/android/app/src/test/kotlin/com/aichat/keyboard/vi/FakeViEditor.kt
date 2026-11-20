/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

/**
 * In-memory editor for [ViInputController] tests. It records every call the controller (or the
 * simulated FlorisBoard glue in [ViTestKeyboard]) makes, like a fake InputConnection would.
 */
class FakeViEditor : ViEditorPort {
    sealed interface Call {
        /** `setComposingRegion(cursor - deleteCount, cursor)` + `setComposingText(text)`. */
        data class Replace(val deleteCount: Int, val text: String) : Call

        /** Plain `commitText(text)` by FlorisBoard (space, enter, ...). */
        data class Commit(val text: String) : Call

        /** Plain one-char backspace by FlorisBoard. */
        data object Delete : Call
    }

    private val buffer = StringBuilder()
    var cursor = 0
        private set
    val calls = mutableListOf<Call>()
    var rejectEdits = false

    val text: String get() = buffer.toString()

    override fun textBeforeCursor(): CharSequence = buffer.substring(0, cursor)

    override fun cursorPosition(): Int = cursor

    override fun replaceBeforeCursor(deleteCount: Int, text: String): Boolean {
        if (rejectEdits || deleteCount > cursor) return false
        calls += Call.Replace(deleteCount, text)
        buffer.replace(cursor - deleteCount, cursor, text)
        cursor += text.length - deleteCount
        return true
    }

    fun commit(text: String) {
        calls += Call.Commit(text)
        buffer.insert(cursor, text)
        cursor += text.length
    }

    fun deleteOne() {
        if (cursor == 0) return
        calls += Call.Delete
        buffer.deleteCharAt(cursor - 1)
        cursor--
    }

    /** Moves the cursor without telling anyone (the test delivers the selection update itself). */
    fun moveCursorSilently(to: Int) {
        cursor = to.coerceIn(0, buffer.length)
    }

    /** Another app rewrites the field, e.g. a chat app clearing it after "send". */
    fun replaceAllExternally(newText: String) {
        buffer.setLength(0)
        buffer.append(newText)
        cursor = newText.length
    }
}

/**
 * Drives [ViInputController] the way FlorisBoard's `EditorInstance` does (see the `AIChat MODIFIED`
 * hooks there): letters go through the composer adapter like `AbstractEditorInstance.commitChar`,
 * space/enter commit through `commitText` after `finishWord`, backspace tries the engine first.
 */
class ViTestKeyboard(var settings: ViInputSettings = ViInputSettings.DEFAULT) {
    val editor = FakeViEditor()
    val controller = ViInputController { settings }

    /** One key press. Space and enter take the `commitText` path, like `KeyboardManager.handleSpace`/`performEnter`. */
    fun key(key: String) {
        when (key) {
            " ", "\n" -> {
                controller.finishWord(editor)
                editor.commit(key)
            }
            else -> commitChar(key)
        }
    }

    /** Types each char (or each surrogate pair) of [keys] as one key. */
    fun type(keys: String) {
        var i = 0
        while (i < keys.length) {
            val end = i + Character.charCount(keys.codePointAt(i))
            key(keys.substring(i, end))
            i = end
        }
    }

    fun backspace() {
        if (!controller.onBackspace(editor)) {
            controller.reset()
            editor.deleteOne()
        }
    }

    /** User taps somewhere in the text: the editor moves the cursor and reports the selection change. */
    fun tapAt(position: Int) {
        val old = editor.cursor
        editor.moveCursorSilently(position)
        controller.onSelectionUpdate(old, old, editor.cursor, editor.cursor)
    }

    val composing: String get() = controller.state.composingText

    private fun commitChar(key: String) {
        val adapter = controller.composerAdapter
        val preceding = editor.textBeforeCursor().takeLast(adapter.toRead).toString()
        val (deleteCount, text) = adapter.getActions(preceding, key)
        if (deleteCount <= 0) editor.commit(text) else editor.replaceBeforeCursor(deleteCount, text)
        controller.onEditApplied(editor.cursorPosition())
    }
}
