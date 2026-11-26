/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import java.nio.FloatBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CutoutComposerTest {
    private val red = 0xFFFF0000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun image(width: Int, height: Int, color: Int) = StickerPixels(width, height, IntArray(width * height) { color })

    /** A rectangle subject with the same [confidence] everywhere in its box. */
    private fun rect(left: Int, top: Int, width: Int, height: Int, confidence: Float = 1f) =
        DetectedSubject(left, top, width, height, FloatArray(width * height) { confidence })

    private fun alpha(color: Int) = color ushr 24

    private fun StickerPixels.opaqueBounds(): IntArray {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha(this[x, y]) > 0) {
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
            }
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    // --- subjectAt ---

    @Test
    fun `a point inside a subject mask hits that subject`() {
        val result = SegmentationResult(100, 100, listOf(rect(10, 10, 20, 20), rect(60, 60, 20, 20)))
        assertEquals(0, CutoutComposer.subjectAt(result, 15, 15))
        assertEquals(1, CutoutComposer.subjectAt(result, 70, 79))
    }

    @Test
    fun `background and low-confidence points hit nothing`() {
        val result = SegmentationResult(100, 100, listOf(rect(10, 10, 20, 20), rect(60, 60, 20, 20, confidence = 0.3f)))
        assertNull(CutoutComposer.subjectAt(result, 40, 40))
        assertNull(CutoutComposer.subjectAt(result, 30, 15))
        assertNull(CutoutComposer.subjectAt(result, 70, 70))
    }

    @Test
    fun `overlapping masks pick the higher confidence at the point`() {
        val result = SegmentationResult(100, 100, listOf(rect(0, 0, 50, 50, 0.6f), rect(20, 20, 50, 50, 0.9f)))
        assertEquals(1, CutoutComposer.subjectAt(result, 30, 30))
        assertEquals(0, CutoutComposer.subjectAt(result, 10, 10))
    }

    @Test
    fun `points outside the image hit nothing`() {
        val result = SegmentationResult(100, 100, listOf(rect(0, 0, 100, 100)))
        assertNull(CutoutComposer.subjectAt(result, -1, 5))
        assertNull(CutoutComposer.subjectAt(result, 5, 100))
        assertNull(CutoutComposer.subjectAt(result, 100, 5))
    }

    // --- compose ---

    @Test
    fun `the output is a square of the requested size with a transparent background`() {
        val result = SegmentationResult(200, 100, listOf(rect(50, 25, 100, 50)))
        val out = CutoutComposer.compose(image(200, 100, red), result, emptySet(), outputPx = 64, marginFraction = 0.1f)
        assertEquals(64, out.width)
        assertEquals(64, out.height)
        assertEquals(0, out[0, 0])
        assertEquals(0, out[63, 63])
        assertEquals(red, out[32, 32])
    }

    @Test
    fun `the subject is cropped, fitted inside the margin and centered`() {
        // 100x50 subject into 100px with 10% margins: 80x40 at (10, 30).
        val result = SegmentationResult(300, 300, listOf(rect(120, 40, 100, 50)))
        val out = CutoutComposer.compose(image(300, 300, red), result, emptySet(), outputPx = 100, marginFraction = 0.1f)
        assertContentEquals(intArrayOf(10, 30, 89, 69), out.opaqueBounds())
        assertEquals(red, out[10, 30])
        assertEquals(red, out[89, 69])
    }

    @Test
    fun `selecting one subject leaves the other out`() {
        val source = image(200, 100, red).also { img ->
            for (y in 0 until 100) for (x in 100 until 200) img[x, y] = white
        }
        val result = SegmentationResult(200, 100, listOf(rect(10, 10, 80, 80), rect(110, 10, 80, 80)))
        val out = CutoutComposer.compose(source, result, setOf(1), outputPx = 100, marginFraction = 0f)
        assertTrue(out.argb.all { it == 0 || it and 0xFFFFFF == 0xFFFFFF })
        assertEquals(white, out[50, 50])
        // Only one square subject: it fills the whole canvas when there is no margin.
        assertContentEquals(intArrayOf(0, 0, 99, 99), out.opaqueBounds())
    }

    @Test
    fun `an empty selection takes every subject`() {
        val source = image(200, 100, red).also { img ->
            for (y in 0 until 100) for (x in 100 until 200) img[x, y] = white
        }
        val result = SegmentationResult(200, 100, listOf(rect(10, 10, 80, 80), rect(110, 10, 80, 80)))
        val out = CutoutComposer.compose(source, result, emptySet(), outputPx = 180, marginFraction = 0f)
        assertTrue(out.argb.any { it == red })
        assertTrue(out.argb.any { it == white })
        // The gap between the subjects stays transparent.
        assertEquals(0, alpha(out[90, 90]))
    }

    @Test
    fun `no subject or empty masks give a transparent square`() {
        val source = image(100, 100, red)
        val empty = CutoutComposer.compose(source, SegmentationResult(100, 100, emptyList()), emptySet(), outputPx = 32)
        assertTrue(empty.argb.all { it == 0 })
        val zero = SegmentationResult(100, 100, listOf(rect(10, 10, 50, 50, confidence = 0f)))
        assertTrue(CutoutComposer.compose(source, zero, emptySet(), outputPx = 32).argb.all { it == 0 })
        val unknown = SegmentationResult(100, 100, listOf(rect(10, 10, 50, 50)))
        assertTrue(CutoutComposer.compose(source, unknown, setOf(5), outputPx = 32).argb.all { it == 0 })
    }

    @Test
    fun `soft mask edges become partial alpha`() {
        // Mask fades linearly left to right; at 1:1 scale the alpha follows it.
        val width = 100
        val mask = FloatArray(width * 100) { i -> 0.1f + 0.9f * (i % width) / (width - 1) }
        val result = SegmentationResult(width, 100, listOf(DetectedSubject(0, 0, width, 100, mask)))
        val out = CutoutComposer.compose(image(width, 100, red), result, emptySet(), outputPx = 100, marginFraction = 0f)
        val left = alpha(out[2, 50])
        val right = alpha(out[97, 50])
        assertTrue(left in 20..50, "left alpha $left")
        assertTrue(right > 240, "right alpha $right")
        assertTrue(alpha(out[50, 50]) in 130..170)
    }

    @Test
    fun `source alpha multiplies the mask`() {
        val source = image(50, 50, 0x80FF0000.toInt())
        val result = SegmentationResult(50, 50, listOf(rect(0, 0, 50, 50)))
        val out = CutoutComposer.compose(source, result, emptySet(), outputPx = 50, marginFraction = 0f)
        assertEquals(0x80, alpha(out[25, 25]))
    }

    @Test
    fun `soft edges of a white subject stay white (no dark halo)`() {
        // Radial soft mask, then shrunk 3x (box pre-shrink + bilinear) and grown (bilinear only).
        val size = 300
        val mask = FloatArray(size * size) { i ->
            val dx = i % size - size / 2f
            val dy = i / size - size / 2f
            (1f - (kotlin.math.sqrt(dx * dx + dy * dy) - 80f) / 60f).coerceIn(0f, 1f)
        }
        val result = SegmentationResult(size, size, listOf(DetectedSubject(0, 0, size, size, mask)))
        for (outputPx in listOf(100, 600)) {
            val out = CutoutComposer.compose(image(size, size, white), result, emptySet(), outputPx, 0.08f)
            var soft = 0
            for (c in out.argb) {
                if (alpha(c) == 0) continue
                if (alpha(c) < 255) soft++
                assertEquals(0xFFFFFF, c and 0xFFFFFF, "pixel ${Integer.toHexString(c)} at $outputPx")
            }
            assertTrue(soft > 0, "expected a soft edge at $outputPx")
        }
    }

    @Test
    fun `composing a large photo is fast`() {
        val width = 1600
        val height = 1200
        val source = StickerPixels(width, height, IntArray(width * height) { i -> 0xFF000000.toInt() or (i * 2654435761L).toInt() })
        val mask = FloatArray(1200 * 1000) { i -> (i % 1200) / 1200f }
        val result = SegmentationResult(width, height, listOf(DetectedSubject(200, 100, 1200, 1000, mask), rect(0, 0, 400, 400)))
        CutoutComposer.compose(source, result, emptySet()) // warm-up (JIT)
        val start = System.nanoTime()
        CutoutComposer.compose(source, result, emptySet())
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1000, "compose took $ms ms")
    }

    // --- ML Kit mapping ---

    @Test
    fun `ML Kit masks are copied into detected subjects`() {
        val buffer = FloatBuffer.wrap(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f))
        buffer.position(4) // a consumed buffer is still read from the start
        val subject = MlKitSubjectSegmenter.detectedSubject(5, 7, 3, 2, buffer)!!
        assertEquals(5, subject.left)
        assertEquals(7, subject.top)
        assertEquals(0.6f, subject.confidenceAt(7, 8))
        assertEquals(4, buffer.position())
    }

    @Test
    fun `malformed ML Kit subjects are skipped`() {
        assertNull(MlKitSubjectSegmenter.detectedSubject(0, 0, 3, 2, FloatBuffer.allocate(5)))
        assertNull(MlKitSubjectSegmenter.detectedSubject(0, 0, 0, 2, FloatBuffer.allocate(0)))
        assertNull(MlKitSubjectSegmenter.detectedSubject(0, 0, 3, 2, null))
    }
}
