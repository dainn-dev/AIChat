package com.aichat.keyboard.sticker

import kotlin.test.Test
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
    fun `an editor that declares no content types accepts nothing`() {
        assertFalse(StickerMime.accepts(emptyArray(), "image/png"))
        assertFalse(StickerMime.accepts(null, "image/png"))
    }
}
