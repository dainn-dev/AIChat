/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import com.aichat.keyboard.vi.FakeViEditor.Call.Commit
import com.aichat.keyboard.vi.FakeViEditor.Call.Delete
import com.aichat.keyboard.vi.FakeViEditor.Call.Replace
import com.aichat.vi.InputMethod
import com.aichat.vi.ToneStyle
import com.aichat.vi.VietnameseComposer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViInputControllerTest {
    private val vni = ViInputSettings(inputMethod = InputMethod.VNI)

    @Test
    fun `vieetj then space commits viet with a space`() {
        val kb = ViTestKeyboard()

        kb.type("vieetj ")

        assertEquals("việt ", kb.editor.text)
        assertEquals(
            listOf(
                Commit("v"),
                Replace(1, "vi"),
                Replace(2, "vie"),
                Replace(3, "viê"),
                Replace(3, "viêt"),
                Replace(4, "việt"),
                Commit(" "),
            ),
            kb.editor.calls,
        )
        assertEquals("", kb.composing)
    }

    @Test
    fun `each key rewrites only the composing word`() {
        val kb = ViTestKeyboard()

        kb.type("xin chaof")

        assertEquals("xin chào", kb.editor.text)
        assertEquals("chào", kb.composing)
        assertEquals(Replace(4, "chào"), kb.editor.calls.last())
    }

    @Test
    fun `backspace mid-word goes through the engine`() {
        val kb = ViTestKeyboard()
        kb.type("vieetj")

        kb.backspace()

        assertEquals("việ", kb.editor.text)
        assertEquals(Replace(4, "việ"), kb.editor.calls.last())
        kb.type("t ")
        assertEquals("việt ", kb.editor.text)
    }

    @Test
    fun `backspace removes a letter with its marks`() {
        val kb = ViTestKeyboard()
        kb.type("dduowngf")
        assertEquals("đường", kb.editor.text)

        repeat(2) { kb.backspace() }

        assertEquals("đườ", kb.editor.text)
        repeat(3) { kb.backspace() }
        assertEquals("", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `backspace after the word is committed deletes normally`() {
        val kb = ViTestKeyboard()
        kb.type("vieetj ")

        kb.backspace()
        kb.backspace()

        assertEquals("việ", kb.editor.text)
        assertEquals(listOf(Delete, Delete), kb.editor.calls.takeLast(2))
    }

    @Test
    fun `emoji commits the word and passes through unchanged`() {
        val kb = ViTestKeyboard()
        kb.type("uwf")
        assertEquals("ừ", kb.composing)

        kb.key("😀")

        assertEquals("ừ😀", kb.editor.text)
        assertEquals(Replace(1, "ừ😀"), kb.editor.calls.last())
        assertEquals("", kb.composing)
        kb.type("aa")
        assertEquals("ừ😀â", kb.editor.text)
    }

    @Test
    fun `emoji between words keeps both words intact`() {
        val kb = ViTestKeyboard()

        kb.type("tooi😀banj")

        assertEquals("tôi😀bạn", kb.editor.text)
    }

    @Test
    fun `cursor move resets the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.tapAt(0)
        kb.type("e")

        // The new "e" is a fresh word at the start; "vie" is not turned into "viê".
        assertEquals("evie", kb.editor.text)
        assertEquals("e", kb.composing)
    }

    @Test
    fun `cursor move inside the word resets the word`() {
        val kb = ViTestKeyboard()
        kb.type("vieet")

        kb.tapAt(2)
        kb.type("j")

        assertEquals("vijêt", kb.editor.text)
    }

    @Test
    fun `belated selection updates from fast typing do not reset`() {
        val kb = ViTestKeyboard()
        kb.type("vi")

        // The editor reports the updates for the first keys only after the second key was applied.
        kb.deliverUpdates()
        kb.type("eetj")

        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `belated updates across a word break do not reset the next word`() {
        val kb = ViTestKeyboard()
        kb.type("a tooi")

        kb.deliverUpdates(2) // updates for "a" and " " arrive while "tooi" is composing
        kb.type("j")
        kb.deliverUpdates()
        kb.type(" ")

        assertEquals("a tội ", kb.editor.text)
    }

    @Test
    fun `out-of-order composing-only update keeps the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")
        kb.deliverUpdates()

        // FlorisBoard re-sets the composing region late; the editor repeats an old selection.
        kb.controller.onSelectionUpdate(1, 1, 1, 1, isExpectedByEditor = false)
        kb.type("etj")

        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `batched update with a stale old selection still detects a tap`() {
        val kb = ViTestKeyboard()
        kb.type("xin vie")

        // Chrome/WebView: no update was sent for the typing, then one update for the tap with old = 0.
        kb.editor.pendingUpdates.clear()
        kb.editor.select(0)
        kb.deliverBatchedUpdate()

        assertEquals("", kb.composing)
        kb.type("e")
        assertEquals("exin vie", kb.editor.text)
    }

    @Test
    fun `stale tap onto a position still pending is caught by the text check`() {
        val kb = ViTestKeyboard()
        kb.type("xin vie")

        // Position 1 is still in FlorisBoard's expected queue, so the update looks belated; the
        // check on the next key (text before the cursor must end with the word) catches the move.
        kb.editor.pendingUpdates.clear()
        kb.editor.select(1)
        kb.deliverBatchedUpdate()
        kb.type("e")

        assertEquals("xein vie", kb.editor.text)
        assertEquals("e", kb.composing)
    }

    @Test
    fun `batched update that lands on our own position keeps the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.deliverBatchedUpdate() // old = 0 (stale), new = 3 = where our last edit put the cursor
        kb.type("etj")

        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `selection update that only changes the composing region keeps the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")
        kb.deliverUpdates()

        kb.controller.onSelectionUpdate(3, 3, 3, 3, isExpectedByEditor = false)
        kb.type("etj")

        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `selecting text resets the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")
        kb.deliverUpdates()

        kb.editor.select(0, 3)
        kb.deliverBatchedUpdate()

        assertEquals("", kb.composing)
    }

    @Test
    fun `typing over a selection bypasses the engine`() {
        val kb = ViTestKeyboard()
        kb.type("vie ")
        kb.editor.select(0, 3)
        kb.deliverBatchedUpdate()

        kb.type("a")

        assertEquals("a ", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `backspace with a selection deletes the selection`() {
        val kb = ViTestKeyboard()
        kb.type("vieetj")
        kb.editor.select(0, 2)

        kb.backspace()

        assertEquals("ệt", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `edit without a known cursor drops the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.controller.onEditApplied(ViInputController.NO_CURSOR)

        assertEquals("", kb.composing)
    }

    @Test
    fun `text changed by the app starts a fresh word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.editor.replaceAllExternally("")
        kb.type("e")

        assertEquals("e", kb.editor.text)
    }

    @Test
    fun `candidate tap resets the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.controller.reset() // EditorInstance.commitCompletion
        kb.editor.replaceAllExternally("việt ")
        kb.type("aa")

        assertEquals("việt â", kb.editor.text)
    }

    @Test
    fun `VNI digits place marks and tones`() {
        val kb = ViTestKeyboard(vni)

        kb.type("Vie65t Nam")

        assertEquals("Việt Nam", kb.editor.text)
    }

    @Test
    fun `VNI d9 and tone removal with 0`() {
        val kb = ViTestKeyboard(vni)

        kb.type("d9u7o72ng ")
        kb.type("ca1 ca10 ")

        assertEquals("đường cá ca ", kb.editor.text)
    }

    @Test
    fun `Telex digits are word breaks`() {
        val kb = ViTestKeyboard()

        kb.type("abc1")

        assertEquals("abc1", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `punctuation commits the word`() {
        val kb = ViTestKeyboard()

        kb.type("xinh, ddepj.")

        assertEquals("xinh, đẹp.", kb.editor.text)
    }

    @Test
    fun `English word is restored on space`() {
        val kb = ViTestKeyboard()
        kb.type("user")
        assertEquals("uẻ", kb.editor.text) // shown while typing

        kb.key(" ")

        assertEquals("user ", kb.editor.text)
        assertEquals(listOf(Replace(2, "user"), Commit(" ")), kb.editor.calls.takeLast(2))
    }

    @Test
    fun `English words stay English`() {
        val kb = ViTestKeyboard()

        kb.type("class street window wifi ")

        assertEquals("class street window wifi ", kb.editor.text)
    }

    @Test
    fun `English word is restored before enter and punctuation`() {
        val kb = ViTestKeyboard()

        kb.type("user\nuser.")

        assertEquals("user\nuser.", kb.editor.text)
    }

    @Test
    fun `auto-restore off keeps the engine output`() {
        val kb = ViTestKeyboard(ViInputSettings(autoRestoreEnglish = false))

        kb.type("user ")

        assertEquals("uẻ ", kb.editor.text)
    }

    @Test
    fun `tone style setting applies to the next word without restart`() {
        val kb = ViTestKeyboard()
        kb.type("hoaf ")

        kb.settings = kb.settings.copy(toneStyle = ToneStyle.CLASSIC)
        kb.type("hoaf ")

        assertEquals("hoà hòa ", kb.editor.text)
    }

    @Test
    fun `input method setting applies without restart`() {
        val kb = ViTestKeyboard()
        kb.type("vieetj ")

        kb.settings = vni
        kb.type("vie65t ")

        assertEquals("việt việt ", kb.editor.text)
    }

    @Test
    fun `settings change mid-word starts a fresh word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.settings = vni
        kb.type("e")

        assertEquals("viee", kb.editor.text)
        assertEquals("e", kb.composing)
    }

    @Test
    fun `uppercase is preserved`() {
        val kb = ViTestKeyboard()

        kb.type("VIEETJ DDuowngf ")

        assertEquals("VIỆT Đường ", kb.editor.text)
    }

    @Test
    fun `long words are force-committed at the engine cap`() {
        val kb = ViTestKeyboard()
        val keys = "b".repeat(VietnameseComposer.MAX_WORD_KEYS + 10)

        kb.type(keys)

        assertEquals(keys, kb.editor.text)
        assertTrue(kb.composing.length <= VietnameseComposer.MAX_WORD_KEYS)
        assertTrue(kb.editor.calls.filterIsInstance<Replace>().all { it.deleteCount <= VietnameseComposer.MAX_WORD_KEYS })
    }

    @Test
    fun `finishing an empty word does nothing`() {
        val kb = ViTestKeyboard()

        kb.controller.finishWord(kb.editor)

        assertTrue(kb.editor.calls.isEmpty())
    }

    @Test
    fun `backspace falls back to a normal delete when the editor rejects the edit`() {
        val kb = ViTestKeyboard()
        kb.type("vieetj")
        kb.editor.rejectEdits = true

        val handled = kb.controller.onBackspace(kb.editor)

        assertFalse(handled)
        assertEquals("", kb.composing)
        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `adapter reads enough preceding text for the longest word`() {
        val kb = ViTestKeyboard()

        assertTrue(kb.controller.composerAdapter.toRead >= VietnameseComposer.MAX_WORD_KEYS)
        assertEquals(ViInputComposer.id, kb.controller.composerAdapter.id)
    }

    @Test
    fun `phantom space before a new word is kept outside the word`() {
        val kb = ViTestKeyboard()
        kb.type("xin")
        kb.controller.reset() // candidate "xin" picked; FlorisBoard activates phantom space

        kb.key("c", spaceBefore = true)
        kb.type("hafo")

        assertEquals("xin chào", kb.editor.text)
    }

    @Test
    fun `auto space after punctuation does not leak into the next word`() {
        val kb = ViTestKeyboard()
        kb.type("xinh")

        kb.key(",", spaceAfter = true)
        kb.type("ddepj")

        assertEquals("xinh, đẹp", kb.editor.text)
    }

    @Test
    fun `supplementary-plane key commits the word`() {
        val kb = ViTestKeyboard()
        kb.type("dduowngf")

        kb.key("\uD835\uDD4F") // MATHEMATICAL DOUBLE-STRUCK CAPITAL X: one code point, two chars

        assertEquals("đường\uD835\uDD4F", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `multi-code-point key finishes the word first`() {
        val kb = ViTestKeyboard()
        kb.type("user")

        kb.key("\uD83D\uDC4D\uD83C\uDFFD") // thumbs up + skin tone: two code points

        assertEquals("user\uD83D\uDC4D\uD83C\uDFFD", kb.editor.text)
        assertEquals(Replace(2, "user"), kb.editor.calls[kb.editor.calls.size - 2])
    }

    @Test
    fun `failed final replacement still ends the word`() {
        val kb = ViTestKeyboard()
        kb.type("user")
        kb.editor.rejectEdits = true

        kb.controller.finishWord(kb.editor)

        assertEquals("uẻ", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `failed key replacement does not corrupt the next key`() {
        val kb = ViTestKeyboard()
        kb.type("vie")
        kb.editor.rejectEdits = true
        kb.type("e") // engine state says "viê" but the editor still shows "vie"
        kb.editor.rejectEdits = false

        kb.type("t")

        // The text no longer ends with the composing word, so the engine starts a fresh word.
        assertEquals("viet", kb.editor.text)
    }

    @Test
    fun `switching subtype mid-word commits the final text`() {
        val kb = ViTestKeyboard()
        kb.type("user")

        kb.engineActive = false // globe key: English subtype
        kb.type("s")

        assertEquals("users", kb.editor.text)
        assertEquals("", kb.composing)
    }

    @Test
    fun `undo after a replacement starts a fresh word`() {
        val kb = ViTestKeyboard()
        kb.type("vieetj")

        kb.controller.reset() // EditorInstance.performUndo
        kb.editor.replaceAllExternally("viêt") // the editor undid the last replacement
        kb.type("s")

        assertEquals("viêts", kb.editor.text)
    }

    @Test
    fun `redo after a replacement starts a fresh word`() {
        val kb = ViTestKeyboard()
        kb.type("vieet")

        kb.controller.reset() // EditorInstance.performRedo
        kb.editor.replaceAllExternally("việt")
        kb.type("j")

        assertEquals("việtj", kb.editor.text)
    }

    @Test
    fun `finishWord after partial telex then new keys compose fresh`() {
        val kb = ViTestKeyboard()
        kb.type("dduw")

        // EditorInstance.commitGesture: finishWord, then a plain commitText of the glide word.
        kb.controller.finishWord(kb.editor)
        kb.editor.commit(" được")
        kb.type("s")

        assertEquals("đư đượcs", kb.editor.text)
        assertEquals("s", kb.composing)
    }
}
