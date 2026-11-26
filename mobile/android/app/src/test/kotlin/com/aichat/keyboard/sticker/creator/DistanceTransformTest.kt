/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistanceTransformTest {
    @Test
    fun `a single feature pixel gives the Euclidean distance to it`() {
        val w = 5
        val h = 5
        val feature = BooleanArray(w * h).also { it[2 * w + 2] = true }
        val d = DistanceTransform.euclidean(feature, w, h)
        assertEquals(0f, d[2 * w + 2])
        assertEquals(1f, d[2 * w + 3], 1e-6f)
        assertEquals(sqrt(2f), d[1 * w + 1], 1e-6f)
        assertEquals(sqrt(8f), d[0], 1e-6f)
        assertEquals(sqrt(5f), d[0 * w + 3], 1e-6f)
    }

    @Test
    fun `the nearest of several features wins`() {
        val w = 7
        val h = 1
        val feature = BooleanArray(w).also {
            it[0] = true
            it[6] = true
        }
        val d = DistanceTransform.euclidean(feature, w, h)
        assertEquals(listOf(0f, 1f, 2f, 3f, 2f, 1f, 0f), d.toList())
    }

    @Test
    fun `no feature pixel means infinite distance everywhere`() {
        val d = DistanceTransform.euclidean(BooleanArray(12), 4, 3)
        assertTrue(d.all { it == Float.POSITIVE_INFINITY })
    }

    @Test
    fun `random masks match brute force`() {
        val random = Random(42)
        repeat(20) {
            val w = random.nextInt(1, 24)
            val h = random.nextInt(1, 24)
            val feature = BooleanArray(w * h) { random.nextFloat() < 0.08f }
            if (feature.none { it }) feature[random.nextInt(w * h)] = true
            val d = DistanceTransform.euclidean(feature, w, h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    var best = Float.MAX_VALUE
                    for (fy in 0 until h) for (fx in 0 until w) {
                        if (!feature[fy * w + fx]) continue
                        val dx = (x - fx).toFloat()
                        val dy = (y - fy).toFloat()
                        best = minOf(best, sqrt(dx * dx + dy * dy))
                    }
                    assertEquals(best, d[y * w + x], 1e-4f, "at ($x, $y) in ${w}x$h")
                }
            }
        }
    }
}
