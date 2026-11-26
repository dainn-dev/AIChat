package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuiltInStickersTest {
    private val packs = BuiltInStickers.packs

    @Test
    fun `there is at least one text pack and one emoji pack`() {
        assertTrue(packs.any { pack -> pack.stickers.all { it.source is StickerSource.Text } })
        assertTrue(packs.any { pack -> pack.stickers.all { it.source is StickerSource.Emoji } })
    }

    @Test
    fun `pack and sticker ids are unique`() {
        assertEquals(packs.size, packs.map { it.id }.toSet().size)
        val stickerIds = packs.flatMap { it.stickers }.map { it.id }
        assertEquals(stickerIds.size, stickerIds.toSet().size)
    }

    @Test
    fun `sticker ids are safe to use as cache file names`() {
        packs.flatMap { it.stickers }.forEach { sticker ->
            assertTrue(Regex("[a-z0-9_.-]+").matches(sticker.cacheKey), sticker.cacheKey)
        }
    }

    @Test
    fun `every sticker has a description for accessibility and the commit`() {
        packs.flatMap { it.stickers }.forEach { assertTrue(it.description.isNotBlank(), it.id) }
    }
}
