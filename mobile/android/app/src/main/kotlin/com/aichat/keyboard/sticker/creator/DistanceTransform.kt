/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Exact Euclidean distance transform (Felzenszwalb & Huttenlocher, "Distance Transforms of Sampled Functions").
 * Linear time, and unlike a blurred mask the distance grows evenly in every direction, so offset outlines stay round
 * and the same width around corners and thin parts.
 */
internal object DistanceTransform {
    private const val INF = 1e20

    /**
     * Distance from every pixel center to the nearest pixel where [feature] is true (0 on feature pixels);
     * [Float.POSITIVE_INFINITY] everywhere when there is no feature pixel.
     */
    fun euclidean(feature: BooleanArray, width: Int, height: Int): FloatArray {
        require(feature.size == width * height)
        val grid = DoubleArray(width * height) { if (feature[it]) 0.0 else INF }
        val len = max(width, height)
        val f = DoubleArray(len)
        val d = DoubleArray(len)
        val v = IntArray(len)
        val z = DoubleArray(len + 1)
        for (x in 0 until width) {
            for (y in 0 until height) f[y] = grid[y * width + x]
            transform1d(f, height, d, v, z)
            for (y in 0 until height) grid[y * width + x] = d[y]
        }
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) f[x] = grid[row + x]
            transform1d(f, width, d, v, z)
            for (x in 0 until width) grid[row + x] = d[x]
        }
        return FloatArray(width * height) { if (grid[it] >= INF / 2) Float.POSITIVE_INFINITY else sqrt(grid[it]).toFloat() }
    }

    /** Lower envelope of the parabolas rooted at (q, f[q]); writes squared distances to [d]. */
    private fun transform1d(f: DoubleArray, n: Int, d: DoubleArray, v: IntArray, z: DoubleArray) {
        var k = 0
        v[0] = 0
        z[0] = Double.NEGATIVE_INFINITY
        z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            val fq = f[q] + q.toDouble() * q
            var s: Double
            while (true) {
                val vk = v[k]
                s = (fq - (f[vk] + vk.toDouble() * vk)) / (2.0 * (q - vk))
                if (s <= z[k] && k > 0) k-- else break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toDouble()
            d[q] = dq * dq + f[v[k]]
        }
    }
}
