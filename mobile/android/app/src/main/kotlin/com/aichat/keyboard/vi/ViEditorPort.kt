/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

/**
 * The few editor operations the Vietnamese input controller needs. The production implementation
 * ([EditorInstanceViPort]) goes through FlorisBoard's `AbstractEditorInstance`; unit tests use a fake.
 */
interface ViEditorPort {
    /** Text before the cursor as the keyboard currently knows it, or null if unknown or a selection is active. */
    fun textBeforeCursor(): CharSequence?

    /** Absolute cursor position, or [ViInputController.NO_CURSOR] if unknown or a selection is active. */
    fun cursorPosition(): Int

    /**
     * Replaces the [deleteCount] chars right before the cursor with [text] and places the cursor after it.
     * Implementations do this with `setComposingRegion` + `setComposingText` so the word is rewritten in place.
     *
     * @return false if the editor could not apply the change.
     */
    fun replaceBeforeCursor(deleteCount: Int, text: String): Boolean
}
