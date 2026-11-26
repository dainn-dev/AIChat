/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Turns segmentation masks into a cut-out sticker. Pure Kotlin (JVM-testable). */
object CutoutComposer {
    /**
     * Mask values at or above this count for the crop bounds. Much lower than [CutoutSpec.MASK_THRESHOLD] so the soft
     * fringe of hair and fur is kept instead of being clipped by the crop.
     */
    internal const val EDGE_THRESHOLD = 0.05f

    /**
     * Index (into [SegmentationResult.subjects]) of the subject under source image point ([x], [y]), or null when the
     * point is background. When masks overlap, the subject with the higher confidence there wins.
     */
    fun subjectAt(result: SegmentationResult, x: Int, y: Int): Int? {
        if (x !in 0 until result.imageWidth || y !in 0 until result.imageHeight) return null
        var best: Int? = null
        var bestConfidence = 0f
        result.subjects.forEachIndexed { index, subject ->
            val c = subject.confidenceAt(x, y)
            if (c >= CutoutSpec.MASK_THRESHOLD && (best == null || c > bestConfidence)) {
                best = index
                bestConfidence = c
            }
        }
        return best
    }

    /**
     * Cuts the [selected] subjects out of [source] (alpha = mask confidence, so edges stay soft), crops to the union of
     * their masks, scales to fit [outputPx] minus [marginFraction] on each side (never upscaling more than needed to
     * fill), and centers the result on a transparent [outputPx] square. Empty [selected] → all subjects.
     *
     * Resampling works on premultiplied colors: averaging straight colors would pull the black of fully transparent
     * pixels into the soft edge and leave a dark halo.
     */
    fun compose(
        source: StickerPixels,
        result: SegmentationResult,
        selected: Set<Int>,
        outputPx: Int = CutoutSpec.OUTPUT_PX,
        marginFraction: Float = CutoutSpec.MARGIN_FRACTION,
    ): StickerPixels {
        require(outputPx > 0) { "outputPx must be positive: $outputPx" }
        val out = StickerPixels.transparent(outputPx, outputPx)
        val subjects = if (selected.isEmpty()) {
            result.subjects
        } else {
            selected.filter { it in result.subjects.indices }.sorted().map { result.subjects[it] }
        }
        val mask = unionMask(source, subjects) ?: return out
        val crop = mask.edgeBounds() ?: return out
        val premultiplied = premultipliedCrop(source, mask, crop)

        val available = max(1f, outputPx * (1f - 2f * marginFraction))
        val scale = min(available / crop.width, available / crop.height)
        val destW = (crop.width * scale).roundToInt().coerceIn(1, outputPx)
        val destH = (crop.height * scale).roundToInt().coerceIn(1, outputPx)
        // Bilinear alone skips source pixels when shrinking by more than 2x (aliasing); a box pre-shrink fixes that.
        val factor = max(1, floor(1f / scale).toInt())
        val reduced = if (factor > 1) premultiplied.boxReduce(factor) else premultiplied
        reduced.bilinearInto(out, (outputPx - destW) / 2, (outputPx - destH) / 2, destW, destH)
        return out
    }

    /** Premultiplied ARGB pixels (each color channel already multiplied by alpha / 255). */
    private class Premultiplied(val width: Int, val height: Int, val argb: IntArray)

    private class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** Max confidence over the chosen subjects, stored only over the union of their boxes to keep memory small. */
    private class Mask(val box: Box, val values: FloatArray) {
        /** Tight bounds (image coordinates) of the values at or above [EDGE_THRESHOLD]; null when there are none. */
        fun edgeBounds(): Box? {
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            val w = box.width
            for (y in 0 until box.height) {
                val row = y * w
                for (x in 0 until w) {
                    if (values[row + x] >= EDGE_THRESHOLD) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        maxY = y
                    }
                }
            }
            if (maxX < 0) return null
            return Box(box.left + minX, box.top + minY, box.left + maxX + 1, box.top + maxY + 1)
        }
    }

    private fun unionMask(source: StickerPixels, subjects: List<DetectedSubject>): Mask? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (s in subjects) {
            left = min(left, max(0, s.left))
            top = min(top, max(0, s.top))
            right = max(right, min(source.width, s.left + s.width))
            bottom = max(bottom, min(source.height, s.top + s.height))
        }
        if (left >= right || top >= bottom) return null
        val box = Box(left, top, right, bottom)
        val values = FloatArray(box.width * box.height)
        for (s in subjects) {
            // Only the part of the subject's box that lies inside the image.
            val x0 = max(s.left, 0)
            val y0 = max(s.top, 0)
            val x1 = min(s.left + s.width, source.width)
            val y1 = min(s.top + s.height, source.height)
            for (y in y0 until y1) {
                val src = (y - s.top) * s.width - s.left
                val dst = (y - top) * box.width - left
                for (x in x0 until x1) {
                    val c = s.confidence[src + x]
                    if (c > values[dst + x]) values[dst + x] = c
                }
            }
        }
        return Mask(box, values)
    }

    /** The [crop] of [source] with alpha = mask x source alpha, premultiplied. */
    private fun premultipliedCrop(source: StickerPixels, mask: Mask, crop: Box): Premultiplied {
        val pixels = IntArray(crop.width * crop.height)
        val maskW = mask.box.width
        for (y in 0 until crop.height) {
            val sy = crop.top + y
            val srcRow = sy * source.width
            val maskRow = (sy - mask.box.top) * maskW - mask.box.left
            val dstRow = y * crop.width
            for (x in 0 until crop.width) {
                val sx = crop.left + x
                val m = mask.values[maskRow + sx].coerceIn(0f, 1f)
                val color = source.argb[srcRow + sx]
                val a = (m * (color ushr 24) + 0.5f).toInt()
                if (a == 0) continue
                val r = ((color shr 16 and 0xFF) * a + 127) / 255
                val g = ((color shr 8 and 0xFF) * a + 127) / 255
                val b = ((color and 0xFF) * a + 127) / 255
                pixels[dstRow + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Premultiplied(crop.width, crop.height, pixels)
    }

    /** Averages [factor] x [factor] blocks; partial blocks at the right and bottom edges average what they have. */
    private fun Premultiplied.boxReduce(factor: Int): Premultiplied {
        val rw = (width + factor - 1) / factor
        val rh = (height + factor - 1) / factor
        val result = IntArray(rw * rh)
        val sumA = IntArray(rw)
        val sumR = IntArray(rw)
        val sumG = IntArray(rw)
        val sumB = IntArray(rw)
        for (ry in 0 until rh) {
            sumA.fill(0)
            sumR.fill(0)
            sumG.fill(0)
            sumB.fill(0)
            val y0 = ry * factor
            val y1 = min(y0 + factor, height)
            for (y in y0 until y1) {
                val row = y * width
                for (x in 0 until width) {
                    val c = argb[row + x]
                    if (c == 0) continue
                    val rx = x / factor
                    sumA[rx] += c ushr 24
                    sumR[rx] += c shr 16 and 0xFF
                    sumG[rx] += c shr 8 and 0xFF
                    sumB[rx] += c and 0xFF
                }
            }
            val rowsInBlock = y1 - y0
            for (rx in 0 until rw) {
                val count = (min((rx + 1) * factor, width) - rx * factor) * rowsInBlock
                val half = count / 2
                val a = (sumA[rx] + half) / count
                if (a == 0) continue
                // Rounding must never leave a premultiplied channel above alpha.
                val r = min((sumR[rx] + half) / count, a)
                val g = min((sumG[rx] + half) / count, a)
                val b = min((sumB[rx] + half) / count, a)
                result[ry * rw + rx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Premultiplied(rw, rh, result)
    }

    /**
     * Bilinear resample into the [destW] x [destH] rectangle of [out] at ([offX], [offY]), un-premultiplying on write.
     * Samples beyond the crop count as transparent so the outer edge fades the same way the mask does.
     */
    private fun Premultiplied.bilinearInto(out: StickerPixels, offX: Int, offY: Int, destW: Int, destH: Int) {
        val stepX = width.toFloat() / destW
        val stepY = height.toFloat() / destH
        val col0 = IntArray(destW)
        val colF = FloatArray(destW)
        for (dx in 0 until destW) {
            val sx = (dx + 0.5f) * stepX - 0.5f
            val x0 = floor(sx).toInt()
            col0[dx] = x0
            colF[dx] = sx - x0
        }
        for (dy in 0 until destH) {
            val sy = (dy + 0.5f) * stepY - 0.5f
            val y0 = floor(sy).toInt()
            val fy = sy - y0
            val outRow = (offY + dy) * out.width + offX
            for (dx in 0 until destW) {
                val x0 = col0[dx]
                val fx = colF[dx]
                val c00 = pixelOrClear(x0, y0)
                val c10 = pixelOrClear(x0 + 1, y0)
                val c01 = pixelOrClear(x0, y0 + 1)
                val c11 = pixelOrClear(x0 + 1, y0 + 1)
                if ((c00 or c10 or c01 or c11) == 0) continue
                val w00 = (1f - fx) * (1f - fy)
                val w10 = fx * (1f - fy)
                val w01 = (1f - fx) * fy
                val w11 = fx * fy
                val a = w00 * (c00 ushr 24) + w10 * (c10 ushr 24) + w01 * (c01 ushr 24) + w11 * (c11 ushr 24)
                val alpha = (a + 0.5f).toInt()
                if (alpha <= 0) continue
                val r = w00 * (c00 shr 16 and 0xFF) + w10 * (c10 shr 16 and 0xFF) +
                    w01 * (c01 shr 16 and 0xFF) + w11 * (c11 shr 16 and 0xFF)
                val g = w00 * (c00 shr 8 and 0xFF) + w10 * (c10 shr 8 and 0xFF) +
                    w01 * (c01 shr 8 and 0xFF) + w11 * (c11 shr 8 and 0xFF)
                val b = w00 * (c00 and 0xFF) + w10 * (c10 and 0xFF) + w01 * (c01 and 0xFF) + w11 * (c11 and 0xFF)
                val unpremultiply = 255f / a
                out.argb[outRow + dx] = (min(alpha, 255) shl 24) or
                    (straight(r, unpremultiply) shl 16) or
                    (straight(g, unpremultiply) shl 8) or
                    straight(b, unpremultiply)
            }
        }
    }

    private fun Premultiplied.pixelOrClear(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) argb[y * width + x] else 0

    private fun straight(premultiplied: Float, unpremultiply: Float): Int =
        (premultiplied * unpremultiply + 0.5f).toInt().coerceIn(0, 255)
}
