/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where an image of some size lands inside a view when scaled to fit (letterboxed), in view pixels. */
data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    /** View pixels per image pixel. */
    fun scale(imageWidth: Int): Float = width / imageWidth
}

/** Size and coordinate arithmetic of the sticker creator. Pure Kotlin so it is unit-tested on the JVM. */
object StickerCreatorMath {
    /**
     * The `inSampleSize` for decoding a [width] x [height] photo whose longer side should end up at most [maxLongSide]:
     * the largest power of two that still leaves the longer side at least [maxLongSide] (the rest is a smooth scale).
     */
    fun sampleSizeForLongSide(width: Int, height: Int, maxLongSide: Int): Int {
        val longest = max(width, height)
        var sample = 1
        while (longest / (sample * 2) >= maxLongSide) {
            sample *= 2
        }
        return sample
    }

    /** The rect an [imageWidth] x [imageHeight] image takes when fitted (centered, aspect kept) in a view. */
    fun fit(viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): FitRect {
        if (viewWidth <= 0f || viewHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return FitRect(0f, 0f, 0f, 0f)
        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return FitRect((viewWidth - width) / 2f, (viewHeight - height) / 2f, width, height)
    }

    /**
     * The image pixel under view point ([x], [y]) when the image is fitted in the view, or null when the point is on
     * the letterbox bars (outside the image).
     */
    fun viewToImage(
        viewWidth: Float,
        viewHeight: Float,
        imageWidth: Int,
        imageHeight: Int,
        x: Float,
        y: Float,
    ): Pair<Int, Int>? {
        val rect = fit(viewWidth, viewHeight, imageWidth, imageHeight)
        if (rect.width <= 0f || rect.height <= 0f) return null
        val ix = floor((x - rect.left) / rect.width * imageWidth).toInt()
        val iy = floor((y - rect.top) / rect.height * imageHeight).toInt()
        if (ix !in 0 until imageWidth || iy !in 0 until imageHeight) return null
        return ix to iy
    }

    /**
     * The "use whole photo" sticker: [source] scaled (area average when shrinking) to fit an [outputPx] square minus
     * [marginFraction] on each side, centered on a transparent canvas, so the effects have room for their outline.
     */
    fun fitIntoSquare(
        source: StickerPixels,
        outputPx: Int = CutoutSpec.OUTPUT_PX,
        marginFraction: Float = CutoutSpec.MARGIN_FRACTION,
    ): StickerPixels {
        val margin = (outputPx * marginFraction).roundToInt()
        val inner = max(1, outputPx - 2 * margin)
        val scale = inner.toFloat() / max(source.width, source.height)
        val width = (source.width * scale).roundToInt().coerceIn(1, inner)
        val height = (source.height * scale).roundToInt().coerceIn(1, inner)
        val offsetX = (outputPx - width) / 2
        val offsetY = (outputPx - height) / 2
        val out = StickerPixels.transparent(outputPx, outputPx)
        val stepX = source.width.toFloat() / width
        val stepY = source.height.toFloat() / height
        for (dy in 0 until height) {
            val y0 = floor(dy * stepY).toInt().coerceIn(0, source.height - 1)
            val y1 = ceil((dy + 1) * stepY).toInt().coerceIn(y0 + 1, source.height)
            for (dx in 0 until width) {
                val x0 = floor(dx * stepX).toInt().coerceIn(0, source.width - 1)
                val x1 = ceil((dx + 1) * stepX).toInt().coerceIn(x0 + 1, source.width)
                out[offsetX + dx, offsetY + dy] = averageArgb(source, x0, y0, x1, y1)
            }
        }
        return out
    }

    /** Alpha-weighted average of the box [x0, x1) x [y0, y1), so transparent pixels do not darken the edges. */
    private fun averageArgb(source: StickerPixels, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var sumA = 0L
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val c = source[x, y]
                val a = (c ushr 24) and 0xFF
                sumA += a
                sumR += ((c shr 16) and 0xFF) * a
                sumG += ((c shr 8) and 0xFF) * a
                sumB += (c and 0xFF) * a
            }
        }
        if (sumA == 0L) return 0
        val count = (x1 - x0) * (y1 - y0)
        val a = (sumA / count).toInt()
        val r = (sumR / sumA).toInt()
        val g = (sumG / sumA).toInt()
        val b = (sumB / sumA).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
