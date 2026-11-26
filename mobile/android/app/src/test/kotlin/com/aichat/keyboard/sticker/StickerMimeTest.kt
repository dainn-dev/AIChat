package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StickerMimeTest {
    @Test
    fun `an exact mime type match is accepted`() {
        assertTrue(StickerMime.accepts(arrayOf("image/png"), "image/png"))
    }

    @Test
    fun `wildcards accept any matching subtype`() {
        assertTrue(StickerMime.accepts(arrayOf("image/*"), "image/png"))
        assertTrue(StickerMime.accepts(arrayOf("*/*"), "image/png"))
    }

    @Test
    fun `matching ignores case and surrounding spaces`() {
        assertTrue(StickerMime.accepts(arrayOf(" Image/PNG "), "image/png"))
    }

    @Test
    fun `other types and malformed entries are rejected`() {
        assertFalse(StickerMime.accepts(arrayOf("image/gif", "video/*", "text/plain"), "image/png"))
        assertFalse(StickerMime.accepts(arrayOf("image", "", "png"), "image/png"))
    }

    @Test
    fun `extensions match the mime types`() {
        assertEquals("webp", StickerMime.extensionFor(StickerMime.WEBP))
        assertEquals("png", StickerMime.extensionFor(StickerMime.PNG))
        assertEquals("gif", StickerMime.extensionFor(StickerMime.GIF))
        assertEquals("jpg", StickerMime.extensionFor(StickerMime.JPEG))
    }

    private fun webpHeader(chunk: String, flags: Int): ByteArray =
        ("RIFF" + "\u0000".repeat(4) + "WEBP" + chunk + "\u0000".repeat(4)).toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(flags.toByte())

    @Test
    fun `an animated WebP is detected from its VP8X flags`() {
        assertTrue(StickerMime.isAnimated(StickerMime.WEBP, webpHeader("VP8X", 0x02)))
        assertTrue(StickerMime.isAnimated(StickerMime.WEBP, webpHeader("VP8X", 0x12)))
    }

    @Test
    fun `static WebP, PNG and JPEG are not animated, GIF always counts as animated`() {
        assertFalse(StickerMime.isAnimated(StickerMime.WEBP, webpHeader("VP8X", 0x10)))
        assertFalse(StickerMime.isAnimated(StickerMime.WEBP, webpHeader("VP8L", 0x02)))
        assertFalse(StickerMime.isAnimated(StickerMime.WEBP, ByteArray(4)))
        assertFalse(StickerMime.isAnimated(StickerMime.PNG, ByteArray(0)))
        assertFalse(StickerMime.isAnimated(StickerMime.JPEG, ByteArray(0)))
        assertTrue(StickerMime.isAnimated(StickerMime.GIF, ByteArray(0)))
    }

    @Test
    fun `an editor that declares no content types accepts nothing`() {
        assertFalse(StickerMime.accepts(emptyArray(), "image/png"))
        assertFalse(StickerMime.accepts(null, "image/png"))
    }
}
