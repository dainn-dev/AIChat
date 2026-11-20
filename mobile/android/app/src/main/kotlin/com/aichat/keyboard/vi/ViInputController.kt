/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import com.aichat.vi.ComposerState
import com.aichat.vi.VietnameseComposer
import dev.patrickgold.florisboard.ime.text.composing.Composer

/**
 * Holds the [ComposerState] of the word being typed in the Vietnamese subtype and turns key presses,
 * backspace, word breaks and cursor moves into edits of that word.
 *
 * The word on screen is always the last `state.composingText.length` chars before the cursor. Every
 * edit replaces exactly those chars ([ViEditorPort.replaceBeforeCursor]), so the controller never relies
 * on the editor keeping a composing region alive. Before each edit it checks that the text before the
 * cursor still ends with the word; if not (another app or the user changed the text), it drops the word
 * and starts fresh instead of corrupting the text.
 *
 * Not thread-safe: FlorisBoard calls the editor on the main thread only.
 *
 * @param settings read on every key, so settings changes apply without restarting the keyboard.
 */
class ViInputController(private val settings: () -> ViInputSettings) {
    companion object {
        const val NO_CURSOR = -1

        /** Chars of preceding text FlorisBoard hands to [composerAdapter]; must cover any composing word. */
        const val PRECEDING_TEXT_LENGTH = VietnameseComposer.MAX_WORD_KEYS * 2
    }

    private var activeSettings: ViInputSettings? = null
    private var composer: VietnameseComposer? = null

    /** Current word. [ComposerState.EMPTY] between words. */
    var state: ComposerState = ComposerState.EMPTY
        private set

    /** Cursor position right after our last edit; used to tell our own selection updates from user moves. */
    private var expectedCursor = NO_CURSOR

    /** Stateful [Composer] that FlorisBoard's `commitChar` drives while the Vietnamese subtype is active. */
    val composerAdapter: Composer = object : Composer {
        override val id = ViInputComposer.id
        override val label = ViInputComposer.label
        override val toRead = PRECEDING_TEXT_LENGTH

        override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> =
            onKey(precedingText, toInsert)
    }

    /**
     * Feeds one key (a single grapheme) to the engine. Returns FlorisBoard's composer action: how many
     * chars before the cursor to replace, and the replacement (committed text + new composing word).
     * The caller must apply it and then call [onEditApplied].
     */
    fun onKey(precedingText: CharSequence, key: String): Pair<Int, String> {
        val composer = currentComposer()
        if (!state.isEmpty && !precedingText.endsWith(state.composingText)) {
            reset()
        }
        val previous = state.composingText
        val outcome = composer.type(state, key)
        state = outcome.state
        return previous.length to (outcome.committed + outcome.state.composingText)
    }

    /** Records where the cursor ended up after an edit made through [composerAdapter]. */
    fun onEditApplied(cursor: Int) {
        if (state.isEmpty || cursor == NO_CURSOR) {
            // Without a known cursor, cursor moves could not be detected: drop the word.
            reset()
        } else {
            expectedCursor = cursor
        }
    }

    /**
     * Backspace while a word is being composed: the engine removes the last letter with its marks
     * (`việt` -> `việ`). Returns false when there is no word, so the caller deletes normally.
     */
    fun onBackspace(editor: ViEditorPort): Boolean {
        if (state.isEmpty) return false
        val composer = currentComposer()
        if (state.isEmpty || !isWordBeforeCursor(editor)) {
            reset()
            return false
        }
        val previous = state.composingText
        val next = composer.backspace(state)
        if (!editor.replaceBeforeCursor(previous.length, next.composingText)) {
            reset()
            return false
        }
        state = next
        onEditApplied(editor.cursorPosition())
        return true
    }

    /**
     * Ends the current word: replaces it with the engine's final text, which can differ from what is
     * shown while typing (`uẻ` -> `user`), and resets. Call before any text is inserted another way
     * (space, enter, paste, emoji, editor action) and when leaving the Vietnamese subtype.
     */
    fun finishWord(editor: ViEditorPort) {
        val composer = composer
        if (state.isEmpty || composer == null) {
            reset()
            return
        }
        val shown = state.composingText
        val final = composer.commitText(state)
        if (final != shown && isWordBeforeCursor(editor)) {
            editor.replaceBeforeCursor(shown.length, final)
        }
        reset()
    }

    /**
     * Selection update from the editor. The word is dropped when the user moves the cursor or selects
     * text. Belated updates for our own earlier edits (fast typing) are recognised the way AOSP LatinIME
     * does it: a real move starts at the position we expect and ends somewhere else.
     */
    fun onSelectionUpdate(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int) {
        if (state.isEmpty) return
        if (newStart == expectedCursor && newEnd == expectedCursor) return
        if (oldStart != expectedCursor || oldEnd != expectedCursor) return
        if (oldStart != newStart || oldEnd != newEnd) {
            reset()
        }
    }

    /** Forgets the current word without touching the editor (field change, candidate picked, ...). */
    fun reset() {
        state = ComposerState.EMPTY
        expectedCursor = NO_CURSOR
    }

    private fun isWordBeforeCursor(editor: ViEditorPort): Boolean =
        editor.textBeforeCursor()?.endsWith(state.composingText) == true

    /** The composer for the current settings. A settings change drops the word typed with the old ones. */
    private fun currentComposer(): VietnameseComposer {
        val wanted = settings()
        val cached = composer
        if (cached != null && wanted == activeSettings) return cached
        reset()
        return wanted.createComposer().also {
            composer = it
            activeSettings = wanted
        }
    }
}
