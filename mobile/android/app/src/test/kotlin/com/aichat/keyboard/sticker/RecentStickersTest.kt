package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentStickersTest {
    @Test
    fun `add puts the sticker first`() {
        assertEquals(listOf("c", "a", "b"), RecentStickers.add(listOf("a", "b"), "c"))
    }

    @Test
    fun `add moves an already recent sticker to the front without duplicating it`() {
        assertEquals(listOf("b", "a", "c"), RecentStickers.add(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `add caps the list and drops the oldest`() {
        val full = (1..RecentStickers.MAX).map { "s$it" }
        val result = RecentStickers.add(full, "new")
        assertEquals(RecentStickers.MAX, result.size)
        assertEquals("new", result.first())
        assertEquals("s${RecentStickers.MAX - 1}", result.last())
    }

    @Test
    fun `add ignores malformed ids`() {
        assertEquals(listOf("a"), RecentStickers.add(listOf("a"), ""))
        assertEquals(listOf("a"), RecentStickers.add(listOf("a"), "x\ny"))
    }

    @Test
    fun `serialize and parse round-trip`() {
        val ids = listOf("builtin-text.haha", "user.0f8fad5b-d9cb-469f-a165-70867728950e", "asset:cats:cat_1.png")
        assertEquals(ids, RecentStickers.parse(RecentStickers.serialize(ids)))
    }

    @Test
    fun `parse tolerates missing and corrupted data`() {
        assertEquals(emptyList(), RecentStickers.parse(null))
        assertEquals(emptyList(), RecentStickers.parse(""))
        assertEquals(listOf("a", "b"), RecentStickers.parse("a\n\n  \na\r\nb\n"))
        assertEquals(RecentStickers.MAX, RecentStickers.parse((1..50).joinToString("\n") { "s$it" }).size)
    }

    @Test
    fun `resolve keeps order and drops unknown ids`() {
        val haha = sticker("builtin-text.haha")
        val joy = sticker("builtin-emoji.joy")
        val packs = listOf(
            StickerPack("text", "Chữ", "Aa", listOf(haha)),
            StickerPack("emoji", "Emoji", "😂", listOf(joy)),
        )
        val resolved = RecentStickers.resolve(listOf("builtin-emoji.joy", "user.deleted", "builtin-text.haha"), packs)
        assertEquals(listOf(joy, haha), resolved)
    }

    @Test
    fun `resolve finds built-in stickers`() {
        val resolved = RecentStickers.resolve(listOf("builtin-text.cam-on", "gone"), BuiltInStickers.packs)
        assertEquals(listOf("builtin-text.cam-on"), resolved.map { it.id })
    }

    private fun sticker(id: String) = Sticker(id = id, description = id, source = StickerSource.Emoji("😀"))
}
