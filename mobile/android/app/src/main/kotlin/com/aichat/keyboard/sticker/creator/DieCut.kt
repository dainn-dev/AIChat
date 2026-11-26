/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry of the white die-cut border and its drop shadow, derived from a cutout's alpha. Sizes scale with the image
 * so a 256 px preview and the 512 px sticker look the same.
 */
internal class DieCut private constructor(
    val width: Int,
    val height: Int,
    /** Cutout alpha (0..1). */
    val subjectAlpha: FloatArray,
    /** Alpha at or above [CutoutSpec.MASK_THRESHOLD]: what the border is grown from. */
    val inside: BooleanArray,
    /** White border coverage (0..1), 1 under the subject too, with a 1 px anti-aliased rim. */
    val border: FloatArray,
    /** Shadow alpha (0..1, already scaled by [SHADOW_OPACITY]). */
    val shadow: FloatArray,
) {
    /** Shorter image side: the unit all effect sizes are expressed in. */
    val size: Int get() = min(width, height)

    /** Draws shadow, then white border, then [subject] onto a new transparent canvas. */
    fun compose(subject: FloatImage): FloatImage {
        val canvas = FloatImage(width, height)
        canvas.fillOver(shadow, 0f, 0f, 0f)
        canvas.fillOver(border, 1f, 1f, 1f)
        canvas.drawOver(subject)
        return canvas
    }

    companion object {
        /** Border thickness as a share of [size]: ≈18 px at 512, like iOS stickers; fits inside the 8% margin. */
        const val BORDER_FRACTION = 0.035f

        /** Shadow offset downwards and blur radius, as shares of [size]. */
        const val SHADOW_OFFSET_FRACTION = 0.01f
        const val SHADOW_BLUR_FRACTION = 0.012f
        const val SHADOW_OPACITY = 0.25f

        fun of(cutout: StickerPixels): DieCut {
            val w = cutout.width
            val h = cutout.height
            val n = w * h
            val size = min(w, h)
            val alpha = FloatArray(n)
            val inside = BooleanArray(n)
            for (i in 0 until n) {
                val a = (cutout.argb[i] ushr 24) / 255f
                alpha[i] = a
                inside[i] = a >= CutoutSpec.MASK_THRESHOLD
            }
            // Distance from the subject's mask, measured between pixel centers. Coverage ramps over 1 px around
            // the target radius, which gives a smooth anti-aliased rim (the offset of a pixel set by ~18 px is
            // already round; its scallops are far below a pixel).
            val dist = DistanceTransform.euclidean(inside, w, h)
            val radius = BORDER_FRACTION * size
            val border = FloatArray(n)
            for (i in 0 until n) border[i] = max(ColorMath.clamp01(radius + 0.5f - dist[i]), alpha[i])

            val offset = max(1, (SHADOW_OFFSET_FRACTION * size).roundToInt())
            val blur = max(1, (SHADOW_BLUR_FRACTION * size).roundToInt())
            val shadow = ImageFilters.boxBlur(ImageFilters.shift(border, w, h, 0, offset), w, h, blur)
            for (i in 0 until n) shadow[i] *= SHADOW_OPACITY
            return DieCut(w, h, alpha, inside, border, shadow)
        }
    }
}
