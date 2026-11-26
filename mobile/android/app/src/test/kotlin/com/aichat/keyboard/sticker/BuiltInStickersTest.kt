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

    @Test
    fun `every built-in sticker has at least one tag`() {
        packs.flatMap { it.stickers }.forEach { assertTrue(it.tags.isNotEmpty(), it.id) }
    }

    @Test
    fun `the text pack has a phrase for most chat moments`() {
        val textStickers = packs.flatMap { it.stickers }.filter { it.source is StickerSource.Text }
        assertTrue(textStickers.size >= 40, "only ${textStickers.size} text stickers")
    }

    @Test
    fun `the original text sticker ids are kept, as recents persist them`() {
        val ids = packs.flatMap { it.stickers }.map { it.id }.toSet()
        listOf("cam-on", "ok-nha", "haha", "yeu-lam", "cho-xiu", "tuyet-voi", "xin-loi", "ngu-ngon", "dang-toi",
            "vang-a", "chuc-mung", "an-com").forEach { assertTrue("builtin-text.$it" in ids, it) }
    }

    @Test
    fun `text stickers are short enough to stay legible`() {
        packs.flatMap { it.stickers }.mapNotNull { it.source as? StickerSource.Text }.forEach { source ->
            val lines = source.text.split('\n')
            assertTrue(lines.size <= 3, source.text)
            lines.forEach { line ->
                assertTrue(line.isNotBlank(), source.text)
                assertTrue(line.codePointCount(0, line.length) <= MAX_LINE_CODE_POINTS, source.text)
            }
        }
    }

    @Test
    fun `text colors are opaque and distinct`() {
        val colors = BuiltInStickers.textColors
        assertTrue(colors.isNotEmpty())
        assertEquals(colors.size, colors.map { it.background }.toSet().size)
        colors.forEach { assertEquals(0xFF, it.background ushr 24) }
    }

    private companion object {
        /** "Chúc mừng" / "Hông chịu" length; longer lines shrink the font. */
        const val MAX_LINE_CODE_POINTS = 10
    }
}
