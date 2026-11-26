package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerTextFitTest {
    private val box = 400f

    @Test
    fun `a short line is capped at the maximum size`() {
        // 2 units wide per 1px of font size: 400 / 2 = 200 > max.
        val size = StickerTextFit.fontSize(listOf(2f), lineHeightPerUnit = 1.2f, box = box, maxSize = 120f, minSize = 24f)
        assertEquals(120f, size)
    }

    @Test
    fun `a long line shrinks so its width fits the box`() {
        val size = StickerTextFit.fontSize(listOf(8f), lineHeightPerUnit = 1.2f, box = box, maxSize = 120f, minSize = 24f)
        assertEquals(50f, size)
        assertTrue(size * 8f <= box)
    }

    @Test
    fun `the widest line decides the width limit`() {
        val size = StickerTextFit.fontSize(listOf(4f, 10f, 2f), lineHeightPerUnit = 1.2f, box = box, maxSize = 120f, minSize = 24f)
        assertEquals(40f, size)
    }

    @Test
    fun `many lines shrink so the block height fits the box`() {
        // 5 lines * 1.25 = 6.25 units tall: 400 / 6.25 = 64 (width allows 400).
        val size = StickerTextFit.fontSize(List(5) { 1f }, lineHeightPerUnit = 1.25f, box = box, maxSize = 120f, minSize = 24f)
        assertEquals(64f, size)
    }

    @Test
    fun `the size never drops below the minimum`() {
        val size = StickerTextFit.fontSize(listOf(1000f), lineHeightPerUnit = 1.2f, box = box, maxSize = 120f, minSize = 24f)
        assertEquals(24f, size)
    }

    @Test
    fun `empty or zero-width text gets the maximum size`() {
        assertEquals(120f, StickerTextFit.fontSize(emptyList(), 1.2f, box, 120f, 24f))
        assertEquals(120f, StickerTextFit.fontSize(listOf(0f), 1.2f, box, 120f, 24f))
    }
}
