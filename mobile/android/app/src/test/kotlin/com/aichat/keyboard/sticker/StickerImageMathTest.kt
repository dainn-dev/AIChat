package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals

class StickerImageMathTest {
    @Test
    fun `small images are decoded at full size`() {
        assertEquals(1, StickerImageMath.sampleSize(width = 400, height = 300, target = 512))
        assertEquals(1, StickerImageMath.sampleSize(width = 1000, height = 600, target = 512))
    }

    @Test
    fun `the sample size is the largest power of two that keeps both sides at or above the target`() {
        assertEquals(2, StickerImageMath.sampleSize(width = 1024, height = 1024, target = 512))
        assertEquals(4, StickerImageMath.sampleSize(width = 4032, height = 3024, target = 512))
    }

    @Test
    fun `fitted sizes keep the aspect ratio and the longer side at most the target`() {
        assertEquals(512 to 384, StickerImageMath.fitInside(width = 1024, height = 768, target = 512))
        assertEquals(256 to 512, StickerImageMath.fitInside(width = 500, height = 1000, target = 512))
    }

    @Test
    fun `images already inside the target are not upscaled`() {
        assertEquals(300 to 200, StickerImageMath.fitInside(width = 300, height = 200, target = 512))
    }

    @Test
    fun `a fitted side never collapses to zero`() {
        assertEquals(512 to 1, StickerImageMath.fitInside(width = 10_000, height = 2, target = 512))
    }
}
