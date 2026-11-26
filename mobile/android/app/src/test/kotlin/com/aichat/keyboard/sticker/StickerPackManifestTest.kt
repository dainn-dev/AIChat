package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerPackManifestTest {
    @Test
    fun `a valid manifest becomes a pack of asset stickers`() {
        val json = """
            {"id": "cats", "title": "Mèo", "icon": "🐱",
             "stickers": [{"file": "hi.png", "description": "Mèo chào"}, {"file": "bye.webp"}]}
        """.trimIndent()

        val pack = StickerPackManifest.parse(json, assetDir = "ime/media/sticker/cats")!!

        assertEquals("asset:cats", pack.id)
        assertEquals("Mèo", pack.title)
        assertEquals("🐱", pack.icon)
        assertEquals(
            listOf(
                StickerSource.Asset("ime/media/sticker/cats/hi.png"),
                StickerSource.Asset("ime/media/sticker/cats/bye.webp"),
            ),
            pack.stickers.map { it.source },
        )
        assertEquals("Mèo chào", pack.stickers[0].description)
        assertEquals("Mèo", pack.stickers[1].description) // falls back to the pack title
    }

    @Test
    fun `entries with unsafe or unsupported file names are skipped`() {
        val json = """
            {"id": "p", "title": "P", "icon": "P",
             "stickers": [{"file": "../x.png"}, {"file": "a/b.png"}, {"file": "c.svg"}, {"file": "ok.png"}]}
        """.trimIndent()

        val pack = StickerPackManifest.parse(json, assetDir = "d")!!

        assertEquals(listOf(StickerSource.Asset("d/ok.png")), pack.stickers.map { it.source })
    }

    @Test
    fun `malformed manifests and empty packs are rejected`() {
        assertNull(StickerPackManifest.parse("not json", "d"))
        assertNull(StickerPackManifest.parse("""{"title": "no id"}""", "d"))
        assertNull(StickerPackManifest.parse("""{"id": "e", "title": "E", "icon": "E", "stickers": []}""", "d"))
    }

    @Test
    fun `a file listed twice becomes one sticker`() {
        val json = """{"id": "p", "title": "P", "icon": "P", "stickers": [{"file": "a.png"}, {"file": "a.png"}]}"""
        assertEquals(1, StickerPackManifest.parse(json, "d")!!.stickers.size)
    }

    @Test
    fun `unknown fields are tolerated`() {
        val json = """{"id": "p", "title": "P", "icon": "P", "author": "me", "stickers": [{"file": "a.png", "x": 1}]}"""
        assertTrue(StickerPackManifest.parse(json, "d") != null)
    }
}
