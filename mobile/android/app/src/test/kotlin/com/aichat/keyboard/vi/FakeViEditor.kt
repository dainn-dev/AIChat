/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

/**
 * In-memory editor for [ViInputController] tests. It records every call the controller (or the
 * simulated FlorisBoard glue in [ViTestKeyboard]) makes, like a fake InputConnection would, and
 * queues the selection updates the real editor would send back asynchronously.
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
    var selectionEnd = 0
        private set
    val calls = mutableListOf<Call>()
    var rejectEdits = false

    /** Cursor positions of edits whose selection update has not been delivered yet. */
    val pendingUpdates = ArrayDeque<Int>()

    /** Last selection the editor reported to the keyboard (the `old` of the next update). */
    var reportedCursor = 0
        private set

    val text: String get() = buffer.toString()
    val hasSelection: Boolean get() = selectionEnd != cursor

    override fun textBeforeCursor(): CharSequence? = if (hasSelection) null else buffer.substring(0, cursor)

    override fun cursorPosition(): Int = if (hasSelection) ViInputController.NO_CURSOR else cursor

    override fun replaceBeforeCursor(deleteCount: Int, text: String): Boolean {
        if (rejectEdits || hasSelection || deleteCount > cursor) return false
        calls += Call.Replace(deleteCount, text)
        buffer.replace(cursor - deleteCount, cursor, text)
        moveTo(cursor + text.length - deleteCount)
        return true
    }

    fun commit(text: String) {
        calls += Call.Commit(text)
        buffer.replace(cursor, selectionEnd, text)
        moveTo(cursor + text.length)
    }

    fun deleteOne() {
        if (hasSelection) {
            commit("")
            return
        }
        if (cursor == 0) return
        calls += Call.Delete
        buffer.deleteCharAt(cursor - 1)
        moveTo(cursor - 1)
    }

    /** User or app moves the cursor / selection; the update is queued like any other. */
    fun select(start: Int, end: Int = start) {
        cursor = start.coerceIn(0, buffer.length)
        selectionEnd = end.coerceIn(cursor, buffer.length)
    }

    /** Another app rewrites the field, e.g. a chat app clearing it after "send". */
    fun replaceAllExternally(newText: String) {
        buffer.setLength(0)
        buffer.append(newText)
        moveTo(newText.length)
    }

    /** Records [position] as the selection last reported to the keyboard. */
    fun report(position: Int) {
        reportedCursor = position
    }

    private fun moveTo(position: Int) {
        cursor = position
        selectionEnd = position
        pendingUpdates.addLast(position)
    }
}

/**
 * Drives [ViInputController] the way FlorisBoard's `EditorInstance` does (see the `AIChat MODIFIED`
 * hooks there): letters go through the composer adapter like `AbstractEditorInstance.commitChar`,
 * space/enter commit through `commitText` after `finishWord`, backspace tries the engine first.
 *
 * Selection updates are not delivered automatically: like a real editor, they arrive later. Use
 * [deliverUpdates] (one update per edit, possibly belated), [deliverBatchedUpdate] (Chrome/WebView
 * style, one update with a stale `old`) or [tapAt].
 */
class ViTestKeyboard(var settings: ViInputSettings = ViInputSettings.DEFAULT) {
    val editor = FakeViEditor()
    val controller = ViInputController { settings }

    /** False emulates another subtype, the symbols layer or a blocked field ([ViInputGate]). */
    var engineActive = true

    /** Edit selections FlorisBoard still expects to be confirmed (its expected-content queue). */
    private val expectedQueue = ArrayDeque<Int>()

    /**
     * One key press. Space and enter take the `commitText` path, like `KeyboardManager.handleSpace`/
     * `performEnter`. [spaceBefore]/[spaceAfter] emulate phantom space and auto-space-after-punctuation,
     * which FlorisBoard adds around the composer's text.
     */
    fun key(key: String, spaceBefore: Boolean = false, spaceAfter: Boolean = false) {
        when (key) {
            " ", "\n" -> {
                controller.finishWord(editor)
                editor.commit(key)
                track()
            }
            else -> commitChar(key, spaceBefore, spaceAfter)
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
        if (!(engineActive && controller.onBackspace(editor))) {
            controller.reset()
            editor.deleteOne()
        }
        track()
    }

    /** Delivers the selection updates of all edits so far, one by one, like a responsive editor. */
    fun deliverUpdates(count: Int = editor.pendingUpdates.size) {
        repeat(count) {
            val position = editor.pendingUpdates.removeFirst()
            val old = editor.reportedCursor
            deliver(old, position)
        }
    }

    /** Delivers a single update from the last reported selection to the current one (stale `old`). */
    fun deliverBatchedUpdate() {
        editor.pendingUpdates.clear()
        deliver(editor.reportedCursor, editor.cursor, editor.selectionEnd)
    }

    /** User taps at [position] after all earlier updates were delivered. */
    fun tapAt(position: Int) {
        deliverUpdates()
        editor.select(position)
        deliverBatchedUpdate()
    }

    val composing: String get() = controller.state.composingText

    private fun deliver(old: Int, newStart: Int, newEnd: Int = newStart) {
        // EditorInstance asks before FlorisBoard pops its queue (AbstractEditorInstance.handleSelectionUpdate).
        val expected = newStart == newEnd && newStart in expectedQueue
        controller.onSelectionUpdate(old, old, newStart, newEnd, isExpectedByEditor = expected)
        if (expected) {
            while (expectedQueue.removeFirst() != newStart) Unit
        } else {
            expectedQueue.clear()
        }
        editor.report(newStart)
    }

    private fun commitChar(key: String, spaceBefore: Boolean, spaceAfter: Boolean) {
        controller.beforeKey(editor, key, engineActive)
        if (!engineActive || editor.hasSelection) {
            editor.commit(key) // upstream Appender / selection replacement
            track()
            return
        }
        val adapter = controller.composerAdapter
        val preceding = (editor.textBeforeCursor() ?: "").takeLast(adapter.toRead).toString()
        val (deleteCount, text) = adapter.getActions(preceding, key)
        val finalText = (if (spaceBefore) " " else "") + text + (if (spaceAfter) " " else "")
        if (deleteCount <= 0) editor.commit(finalText) else editor.replaceBeforeCursor(deleteCount, finalText)
        track()
        controller.onEditApplied(editor.cursorPosition())
    }

    private fun track() {
        expectedQueue.addLast(editor.cursor)
    }
}
