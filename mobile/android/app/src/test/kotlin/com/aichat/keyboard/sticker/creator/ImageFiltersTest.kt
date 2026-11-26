/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageFiltersTest {
    @Test
    fun `box blur spreads a dot symmetrically and conserves its mass away from the edges`() {
        val w = 21
        val h = 21
        val src = FloatArray(w * h).also { it[10 * w + 10] = 1f }
        val out = ImageFilters.boxBlur(src, w, h, radius = 2)
        assertEquals(1.0, out.sumOf { it.toDouble() }, 1e-4)
        assertEquals(out[10 * w + 7], out[10 * w + 13], 1e-6f)
        assertEquals(out[7 * w + 10], out[10 * w + 7], 1e-6f)
        assertTrue(out[10 * w + 10] > out[10 * w + 12])
        assertEquals(0f, out[0])
    }

    @Test
    fun `shift moves values and leaves zeros behind`() {
        val src = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(listOf(0f, 0f, 1f, 2f), ImageFilters.shift(src, 2, 2, 0, 1).toList())
        assertEquals(listOf(0f, 1f, 0f, 3f), ImageFilters.shift(src, 2, 2, 1, 0).toList())
    }

    @Test
    fun `sobel gives 1 on a hard step and 0 on flat areas`() {
        val w = 6
        val h = 3
        val src = FloatArray(w * h) { if (it % w >= 3) 1f else 0f }
        val out = ImageFilters.sobelMagnitude(src, w, h)
        assertEquals(0f, out[1 * w + 0])
        assertEquals(0f, out[1 * w + 5])
        assertEquals(1f, out[1 * w + 2], 1e-6f)
        assertEquals(1f, out[1 * w + 3], 1e-6f)
    }

    @Test
    fun `dilate grows a dot into a 3x3 square`() {
        val src = FloatArray(25).also { it[12] = 1f }
        val out = ImageFilters.dilate3x3(src, 5, 5)
        assertEquals(9, out.count { it == 1f })
        assertEquals(1f, out[6])
        assertEquals(0f, out[0])
    }
}
