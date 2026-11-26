/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import com.aichat.keyboard.sticker.StickerImageMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The selected subjects cut out of the photo at their place: [pixels] goes at ([left], [top]) in source image pixels.
 * The editor draws it slightly scaled up with a shadow, so the selection "lifts" off the photo like on iOS.
 */
class LiftedLayer(val left: Int, val top: Int, val pixels: StickerPixels)

/**
 * The look of the subject picker, drawn over the photo: background dimmed, unselected subjects lightly dimmed with a
 * thin outline, selected subjects clear with a soft white glow around their edge. Pure Kotlin (JVM-tested).
 */
object SelectionOverlay {
    /** The overlay is rendered at most this large (longer side) and stretched over the photo; the blur suits a glow. */
    const val MAX_SIDE = 800

    const val BACKGROUND_DIM = 0.6f
    const val UNSELECTED_DIM = 0.3f

    private const val SELECTED_GLOW_RADIUS = 3
    private const val UNSELECTED_GLOW_RADIUS = 1
    private const val UNSELECTED_GLOW_STRENGTH = 0.55f

    /** The overlay for [result] with the subjects in [selected], same aspect as the photo, longer side ≤ [maxSide]. */
    fun render(result: SegmentationResult, selected: Set<Int>, maxSide: Int = MAX_SIDE): StickerPixels {
        val (width, height) = StickerImageMath.fitInside(result.imageWidth, result.imageHeight, maxSide)
        val scaleX = result.imageWidth.toFloat() / width
        val scaleY = result.imageHeight.toFloat() / height
        val any = FloatArray(width * height)
        val chosen = FloatArray(width * height)
        result.subjects.forEachIndexed { index, subject ->
            val isSelected = index in selected
            val x0 = (subject.left / scaleX).toInt().coerceIn(0, width - 1)
            val y0 = (subject.top / scaleY).toInt().coerceIn(0, height - 1)
            val x1 = ((subject.left + subject.width) / scaleX).toInt().coerceIn(x0, width - 1)
            val y1 = ((subject.top + subject.height) / scaleY).toInt().coerceIn(y0, height - 1)
            for (y in y0..y1) {
                val iy = ((y + 0.5f) * scaleY).toInt()
                for (x in x0..x1) {
                    val c = subject.confidenceAt(((x + 0.5f) * scaleX).toInt(), iy)
                    if (c <= 0f) continue
                    val i = y * width + x
                    if (c > any[i]) any[i] = c
                    if (isSelected && c > chosen[i]) chosen[i] = c
                }
            }
        }

        val glow = FloatArray(width * height)
        addEdgeGlow(any, width, height, UNSELECTED_GLOW_RADIUS, UNSELECTED_GLOW_STRENGTH, glow)
        addEdgeGlow(chosen, width, height, SELECTED_GLOW_RADIUS, 1f, glow)

        val out = IntArray(width * height)
        for (i in out.indices) {
            val unselected = max(0f, any[i] - chosen[i])
            val dim = BACKGROUND_DIM * (1f - any[i]) + UNSELECTED_DIM * unselected
            out[i] = blendWhiteOverBlack(glow[i], dim)
        }
        return StickerPixels(width, height, out)
    }

    /** The selected subjects cut from [source] (alpha = mask confidence), cropped to their boxes; null if none. */
    fun lifted(source: StickerPixels, result: SegmentationResult, selected: Set<Int>): LiftedLayer? {
        val subjects = selected.sorted().mapNotNull { result.subjects.getOrNull(it) }
        if (subjects.isEmpty()) return null
        val left = subjects.minOf { it.left }.coerceIn(0, source.width - 1)
        val top = subjects.minOf { it.top }.coerceIn(0, source.height - 1)
        val right = subjects.maxOf { it.left + it.width }.coerceIn(left + 1, source.width)
        val bottom = subjects.maxOf { it.top + it.height }.coerceIn(top + 1, source.height)
        val layer = StickerPixels.transparent(right - left, bottom - top)
        for (y in top until bottom) {
            for (x in left until right) {
                var confidence = 0f
                for (subject in subjects) confidence = max(confidence, subject.confidenceAt(x, y))
                if (confidence <= 0f) continue
                val color = source[x, y]
                val alpha = (((color ushr 24) and 0xFF) * min(1f, confidence)).toInt()
                layer[x - left, y - top] = (alpha shl 24) or (color and 0xFFFFFF)
            }
        }
        return LiftedLayer(left, top, layer)
    }

    /** Raises [glow] around the edge of the mask `mask >= MASK_THRESHOLD`, fading out over [radius] pixels. */
    private fun addEdgeGlow(mask: FloatArray, width: Int, height: Int, radius: Int, strength: Float, glow: FloatArray) {
        fun inside(x: Int, y: Int) = x in 0 until width && y in 0 until height &&
            mask[y * width + x] >= CutoutSpec.MASK_THRESHOLD
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!inside(x, y)) continue
                val isEdge = !inside(x - 1, y) || !inside(x + 1, y) || !inside(x, y - 1) || !inside(x, y + 1)
                if (!isEdge) continue
                for (dy in -radius..radius) {
                    val gy = y + dy
                    if (gy !in 0 until height) continue
                    for (dx in -radius..radius) {
                        val gx = x + dx
                        if (gx !in 0 until width) continue
                        val distance = sqrt((dx * dx + dy * dy).toFloat())
                        if (distance > radius) continue
                        val value = strength * (1f - distance / (radius + 1))
                        val i = gy * width + gx
                        if (value > glow[i]) glow[i] = value
                    }
                }
            }
        }
    }

    /** Non-premultiplied ARGB of white at [white] opacity over black at [black] opacity. */
    private fun blendWhiteOverBlack(white: Float, black: Float): Int {
        val alpha = white + black * (1f - white)
        if (alpha <= 0f) return 0
        val gray = (255f * white / alpha).toInt().coerceIn(0, 255)
        val a = (alpha * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (gray shl 16) or (gray shl 8) or gray
    }
}
