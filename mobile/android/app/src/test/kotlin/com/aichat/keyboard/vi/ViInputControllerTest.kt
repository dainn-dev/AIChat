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

        // The editor reports the update for the first key (0 -> 1) after the second key was applied.
        kb.controller.onSelectionUpdate(0, 0, 1, 1)
        kb.controller.onSelectionUpdate(1, 1, 2, 2)
        kb.type("eetj")

        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `selection update that only changes the composing region keeps the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.controller.onSelectionUpdate(3, 3, 3, 3)
        kb.type("etj")

        assertEquals("việt", kb.editor.text)
    }

    @Test
    fun `selecting text resets the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.controller.onSelectionUpdate(3, 3, 0, 3)

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
    fun `edit without a known cursor drops the word`() {
        val kb = ViTestKeyboard()
        kb.type("vie")

        kb.controller.onEditApplied(ViInputController.NO_CURSOR)

        assertEquals("", kb.composing)
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
}
