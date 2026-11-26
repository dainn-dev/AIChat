/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Single-channel float filters on row-major planes. Out-of-canvas samples count as 0 (transparent, no height). */
internal object ImageFilters {
    /**
     * Separable box blur repeated [passes] times (3 passes ≈ Gaussian with sigma ≈ radius). Running sums keep it
     * O(pixels) whatever the radius.
     */
    fun boxBlur(src: FloatArray, width: Int, height: Int, radius: Int, passes: Int = 3): FloatArray {
        if (radius <= 0 || passes <= 0) return src.copyOf()
        var cur = src.copyOf()
        val tmp = FloatArray(src.size)
        val line = DoubleArray(max(width, height))
        repeat(passes) {
            blurLines(cur, tmp, width, height, radius, horizontal = true, line)
            blurLines(tmp, cur, width, height, radius, horizontal = false, line)
        }
        return cur
    }

    private fun blurLines(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
        line: DoubleArray,
    ) {
        val n = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val step = if (horizontal) 1 else width
        val inv = 1.0 / (2 * radius + 1)
        for (l in 0 until lines) {
            val base = if (horizontal) l * width else l
            for (k in 0 until n) line[k] = src[base + k * step].toDouble()
            var sum = 0.0
            for (k in 0..min(radius, n - 1)) sum += line[k]
            for (k in 0 until n) {
                dst[base + k * step] = (sum * inv).toFloat()
                val add = k + radius + 1
                if (add < n) sum += line[add]
                val sub = k - radius
                if (sub >= 0) sum -= line[sub]
            }
        }
    }

    /** [src] moved by ([dx], [dy]); uncovered pixels are 0. */
    fun shift(src: FloatArray, width: Int, height: Int, dx: Int, dy: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            val sy = y - dy
            if (sy !in 0 until height) continue
            for (x in 0 until width) {
                val sx = x - dx
                if (sx in 0 until width) out[y * width + x] = src[sy * width + sx]
            }
        }
        return out
    }

    /** 3x3 max filter: thickens thin lines by about a pixel on each side. */
    fun dilate3x3(src: FloatArray, width: Int, height: Int): FloatArray {
        val tmp = FloatArray(src.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var m = src[row + x]
                if (x > 0) m = max(m, src[row + x - 1])
                if (x < width - 1) m = max(m, src[row + x + 1])
                tmp[row + x] = m
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                var m = tmp[i]
                if (y > 0) m = max(m, tmp[i - width])
                if (y < height - 1) m = max(m, tmp[i + width])
                out[i] = m
            }
        }
        return out
    }

    /** Sobel gradient magnitude, normalized so a hard 0→1 step gives 1. Edges are clamped (no false border edge). */
    fun sobelMagnitude(src: FloatArray, width: Int, height: Int): FloatArray {
        val out = FloatArray(src.size)
        for (y in 0 until height) {
            val ym = max(y - 1, 0) * width
            val y0 = y * width
            val yp = min(y + 1, height - 1) * width
            for (x in 0 until width) {
                val xm = max(x - 1, 0)
                val xp = min(x + 1, width - 1)
                val gx = (src[ym + xp] + 2f * src[y0 + xp] + src[yp + xp]) - (src[ym + xm] + 2f * src[y0 + xm] + src[yp + xm])
                val gy = (src[yp + xm] + 2f * src[yp + x] + src[yp + xp]) - (src[ym + xm] + 2f * src[ym + x] + src[ym + xp])
                out[y0 + x] = sqrt(gx * gx + gy * gy) * 0.25f
            }
        }
        return out
    }
}
