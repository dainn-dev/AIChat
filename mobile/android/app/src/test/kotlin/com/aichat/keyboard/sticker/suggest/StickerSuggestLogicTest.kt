package com.aichat.keyboard.sticker.suggest

import com.aichat.keyboard.sticker.BuiltInStickers
import com.aichat.keyboard.sticker.Sticker
import com.aichat.keyboard.sticker.StickerPack
import com.aichat.keyboard.sticker.StickerSource
import com.aichat.keyboard.sticker.StickerTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StickerSuggestLogicTest {
    private fun sticker(id: String, vararg tags: StickerTag) =
        Sticker(id = id, description = id, source = StickerSource.Emoji("🙂"), tags = tags.toSet())

    private val builtIn = StickerPack("b", "B", "B", listOf(sticker("b1", StickerTag.OK), sticker("b2", StickerTag.THANKS, StickerTag.OK)))
    private val asset = StickerPack("a", "A", "A", listOf(sticker("a1", StickerTag.OK), sticker("a2", StickerTag.OK)))

    @Test
    fun `pick takes tag order, then pack order, at most three, no duplicates`() {
        val picked = StickerSuggestLogic.pick(listOf(StickerTag.THANKS, StickerTag.OK), listOf(builtIn, asset))
        assertEquals(listOf("b2", "b1", "a1"), picked.map { it.id })
    }

    @Test
    fun `pick is empty without tags or matching stickers`() {
        assertTrue(StickerSuggestLogic.pick(emptyList(), listOf(builtIn)).isEmpty())
        assertTrue(StickerSuggestLogic.pick(listOf(StickerTag.BUSY), listOf(builtIn, asset)).isEmpty())
    }

    @Test
    fun `built-in stickers cover the common typing tags`() {
        for (tag in listOf(StickerTag.THANKS, StickerTag.OK, StickerTag.LAUGH, StickerTag.LOVE, StickerTag.GOODNIGHT)) {
            assertTrue(StickerSuggestLogic.pick(listOf(tag), BuiltInStickers.packs).isNotEmpty(), "$tag")
        }
    }

    @Test
    fun `tags from the wire drop unknown names and cap at three`() {
        assertEquals(
            listOf(StickerTag.THANKS, StickerTag.LOVE, StickerTag.OK),
            StickerSuggestLogic.tagsFromWire(listOf("thanks", "unicorn", " LOVE ", "thanks", "ok", "laugh")),
        )
        assertTrue(StickerSuggestLogic.tagsFromWire(emptyList()).isEmpty())
    }

    @Test
    fun `success results`() {
        assertTrue(StickerSuggestLogic.isSuccess("SENT"))
        assertTrue(StickerSuggestLogic.isSuccess("SENT_AS_TEXT"))
        assertTrue(StickerSuggestLogic.isSuccess("SHARED"))
        for (name in listOf("UNSUPPORTED", "TARGET_CHANGED", "FAILED")) assertFalse(StickerSuggestLogic.isSuccess(name))
    }

    @Test
    fun `gate hides suggestions in password, raw and incognito fields and when disabled`() {
        assertTrue(StickerSuggestLogic.isAllowed(true, false, false, false))
        assertFalse(StickerSuggestLogic.isAllowed(false, false, false, false))
        assertFalse(StickerSuggestLogic.isAllowed(true, true, false, false))
        assertFalse(StickerSuggestLogic.isAllowed(true, false, true, false))
        assertFalse(StickerSuggestLogic.isAllowed(true, false, false, true))
    }

    @Test
    fun `the trigger is deleted when it is still right before the cursor`() {
        assertEquals(6, StickerSuggestLogic.charsToDelete("cảm ơn", "bạn ơi cảm ơn", hasSelection = false))
        assertEquals(6, StickerSuggestLogic.charsToDelete("cảm ơn", "cảm ơn", hasSelection = false))
        assertEquals(3, StickerSuggestLogic.charsToDelete("ok ", "...ok ", hasSelection = false))
    }

    @Test
    fun `the trigger is kept when the text changed`() {
        // Typed on.
        assertEquals(0, StickerSuggestLogic.charsToDelete("cảm ơn", "cảm ơn nha", hasSelection = false))
        // The fallback text of a text sticker was typed after it.
        assertEquals(0, StickerSuggestLogic.charsToDelete("ok", "ok👍", hasSelection = false))
        // Now part of a longer word.
        assertEquals(0, StickerSuggestLogic.charsToDelete("ok", "kok", hasSelection = false))
        // Text is selected.
        assertEquals(0, StickerSuggestLogic.charsToDelete("ok", "ok", hasSelection = true))
        // Cursor moved elsewhere.
        assertEquals(0, StickerSuggestLogic.charsToDelete("ok", "", hasSelection = false))
        assertEquals(0, StickerSuggestLogic.charsToDelete("", "abc", hasSelection = false))
    }
}
