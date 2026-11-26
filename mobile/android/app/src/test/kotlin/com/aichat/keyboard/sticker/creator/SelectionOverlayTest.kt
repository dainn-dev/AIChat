package com.aichat.keyboard.sticker.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectionOverlayTest {
    private val result = SegmentationResult(
        40,
        20,
        listOf(subject(left = 2, top = 2, width = 12, height = 16), subject(left = 24, top = 2, width = 12, height = 16)),
    )

    private fun alpha(color: Int) = (color ushr 24) / 255f

    @Test
    fun `background is dimmed, selected subjects clear, unselected lightly dimmed`() {
        val overlay = SelectionOverlay.render(result, selected = setOf(0))
        assertEquals(40, overlay.width)
        assertEquals(20, overlay.height)
        assertEquals(SelectionOverlay.BACKGROUND_DIM, alpha(overlay[20, 0]), 0.01f)
        assertEquals(0f, alpha(overlay[8, 10]), 0.01f)
        assertEquals(SelectionOverlay.UNSELECTED_DIM, alpha(overlay[30, 10]), 0.01f)
        assertEquals(0xFF000000.toInt(), overlay[20, 0] or 0xFF000000.toInt(), "the dim is black")
    }

    @Test
    fun `selected subjects glow white at their edge`() {
        val overlay = SelectionOverlay.render(result, selected = setOf(0))
        val edge = overlay[2, 10]
        assertTrue(alpha(edge) > 0.9f)
        assertEquals(0xFF, edge and 0xFF)
        // The glow fades out a few pixels outside the edge.
        assertTrue(alpha(overlay[0, 10]) > SelectionOverlay.BACKGROUND_DIM)
        assertTrue((overlay[0, 10] and 0xFF) < 0xFF)
    }

    @Test
    fun `large photos get a smaller overlay of the same aspect`() {
        val big = SegmentationResult(3200, 1600, listOf(subject(100, 100, 400, 400)))
        val overlay = SelectionOverlay.render(big, selected = setOf(0), maxSide = 800)
        assertEquals(800, overlay.width)
        assertEquals(400, overlay.height)
        assertEquals(0f, alpha(overlay[75, 75]), 0.01f)
    }

    @Test
    fun `the lifted layer is the selected cutout in place`() {
        val photo = StickerPixels(40, 20, IntArray(800) { 0xFF123456.toInt() })
        val half = SegmentationResult(40, 20, listOf(DetectedSubject(4, 6, 2, 1, floatArrayOf(1f, 0.5f))))
        val layer = SelectionOverlay.lifted(photo, half, setOf(0))!!
        assertEquals(4, layer.left)
        assertEquals(6, layer.top)
        assertEquals(2, layer.pixels.width)
        assertEquals(1, layer.pixels.height)
        assertEquals(0xFF123456.toInt(), layer.pixels[0, 0])
        assertEquals(0x7F123456, layer.pixels[1, 0])

        assertNull(SelectionOverlay.lifted(photo, half, emptySet()))
    }

    @Test
    fun `the lifted layer spans every selected subject`() {
        val photo = StickerPixels(40, 20, IntArray(800) { 0xFF000000.toInt() })
        val layer = SelectionOverlay.lifted(photo, result, setOf(0, 1))!!
        assertEquals(2, layer.left)
        assertEquals(34, layer.pixels.width)
        assertEquals(0, layer.pixels[15, 5], "the gap between the subjects stays transparent")
    }

    private companion object {
        fun subject(left: Int, top: Int, width: Int, height: Int) =
            DetectedSubject(left, top, width, height, FloatArray(width * height) { 1f })
    }
}
