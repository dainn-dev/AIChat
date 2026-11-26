package com.aichat.keyboard.sticker.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StickerCreatorMathTest {
    @Test
    fun `sample size keeps the longer side at least the target`() {
        assertEquals(1, StickerCreatorMath.sampleSizeForLongSide(1200, 800, 1600))
        assertEquals(1, StickerCreatorMath.sampleSizeForLongSide(3000, 2000, 1600))
        assertEquals(2, StickerCreatorMath.sampleSizeForLongSide(4000, 3000, 1600))
        assertEquals(4, StickerCreatorMath.sampleSizeForLongSide(3000, 8000, 1600))
    }

    @Test
    fun `fit letterboxes a wide image vertically`() {
        val rect = StickerCreatorMath.fit(400f, 400f, 200, 100)
        assertEquals(FitRect(0f, 100f, 400f, 200f), rect)
        assertEquals(2f, rect.scale(200))
    }

    @Test
    fun `fit pillarboxes a tall image horizontally`() {
        assertEquals(FitRect(150f, 0f, 100f, 200f), StickerCreatorMath.fit(400f, 200f, 50, 100))
    }

    @Test
    fun `a tap maps to the image pixel under it`() {
        // 200x100 image in a 400x400 view: scale 2, image starts at y = 100.
        assertEquals(0 to 0, StickerCreatorMath.viewToImage(400f, 400f, 200, 100, 0f, 100f))
        assertEquals(50 to 25, StickerCreatorMath.viewToImage(400f, 400f, 200, 100, 101f, 151f))
        assertEquals(199 to 99, StickerCreatorMath.viewToImage(400f, 400f, 200, 100, 399.9f, 299.9f))
    }

    @Test
    fun `a tap on the letterbox bars hits nothing`() {
        assertNull(StickerCreatorMath.viewToImage(400f, 400f, 200, 100, 200f, 50f))
        assertNull(StickerCreatorMath.viewToImage(400f, 400f, 200, 100, 200f, 300f))
        assertNull(StickerCreatorMath.viewToImage(400f, 200f, 50, 100, 100f, 100f))
        assertNull(StickerCreatorMath.viewToImage(0f, 0f, 50, 100, 0f, 0f))
    }

    @Test
    fun `the whole photo is centered in the square with a transparent margin`() {
        val red = 0xFFFF0000.toInt()
        val photo = StickerPixels(200, 100, IntArray(200 * 100) { red })
        val sticker = StickerCreatorMath.fitIntoSquare(photo, outputPx = 100, marginFraction = 0.1f)
        assertEquals(100, sticker.width)
        assertEquals(100, sticker.height)
        // Inner square 80: the photo becomes 80x40 at (10, 30).
        assertEquals(0, sticker[5, 50])
        assertEquals(0, sticker[50, 29])
        assertEquals(red, sticker[10, 30])
        assertEquals(red, sticker[89, 69])
        assertEquals(0, sticker[90, 50])
        assertEquals(0, sticker[50, 70])
    }

    @Test
    fun `shrinking averages colors and ignores transparent pixels`() {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val checker = StickerPixels(2, 2, intArrayOf(white, black, black, white))
        val gray = StickerCreatorMath.fitIntoSquare(checker, outputPx = 1, marginFraction = 0f)[0, 0]
        assertEquals(0xFF, gray ushr 24)
        assertEquals(0x7F, gray and 0xFF)

        val half = StickerPixels(2, 1, intArrayOf(0x00000000, white))
        val edge = StickerCreatorMath.fitIntoSquare(half, outputPx = 1, marginFraction = 0f)[0, 0]
        assertEquals(0x7F, edge ushr 24)
        assertEquals(0xFF, edge and 0xFF, "no dark fringe from the transparent pixel")
    }

    @Test
    fun `a small photo is scaled up to fill the square`() {
        val photo = StickerPixels(10, 10, IntArray(100) { 0xFF00FF00.toInt() })
        val sticker = StickerCreatorMath.fitIntoSquare(photo, outputPx = 50, marginFraction = 0f)
        assertEquals(0xFF00FF00.toInt(), sticker[0, 0])
        assertEquals(0xFF00FF00.toInt(), sticker[49, 49])
    }
}
