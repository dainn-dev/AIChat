package com.aichat.keyboard.sticker

import java.io.File
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

    @Test
    fun `tags map to the sticker tag vocabulary and unknown ones are ignored`() {
        val json = """
            {"id": "p", "title": "P", "icon": "P",
             "stickers": [{"file": "a.webp", "tags": ["laugh", " LOVE ", "dance", "laugh"]}, {"file": "b.png"}]}
        """.trimIndent()

        val pack = StickerPackManifest.parse(json, "d")!!

        assertEquals(setOf(StickerTag.LAUGH, StickerTag.LOVE), pack.stickers[0].tags)
        assertEquals(emptySet(), pack.stickers[1].tags) // tags are optional
    }

    @Test
    fun `a tags value of the wrong type rejects the manifest`() {
        val json = """{"id": "p", "title": "P", "stickers": [{"file": "a.png", "tags": "laugh"}]}"""
        assertNull(StickerPackManifest.parse(json, "d"))
    }

    @Test
    fun `the bundled Noto animated pack parses, its files exist and every sticker is tagged`() {
        val dir = File("src/main/assets/ime/media/sticker/noto-animated") // unit tests run from the app module
        val text = File(dir, "pack.json").readText()

        val pack = StickerPackManifest.parse(text, "ime/media/sticker/noto-animated")!!

        val listed = Regex(""""file"\s*:""").findAll(text).count()
        assertEquals(listed, pack.stickers.size) // no entry was dropped as unsafe or unsupported
        for (sticker in pack.stickers) {
            val file = File(dir, (sticker.source as StickerSource.Asset).path.substringAfterLast('/'))
            assertTrue(file.isFile, "missing $file")
            assertTrue(sticker.tags.isNotEmpty(), "untagged ${sticker.id}")
        }
        val unknownTags = Regex(""""tags"\s*:\s*\[([^\]]*)]""").findAll(text)
            .flatMap { Regex(""""([^"]+)"""").findAll(it.groupValues[1]).map { m -> m.groupValues[1] } }
            .filter { StickerTag.fromWire(it) == null }
            .toList()
        assertEquals(emptyList(), unknownTags)
    }
}
