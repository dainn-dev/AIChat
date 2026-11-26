/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.abs

/** Scalar color helpers (channels 0..1). Inline-friendly, allocation-free, for per-pixel loops. */
internal object ColorMath {
    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** Rec. 601 luma: cheap and close enough for edge detection and saturation tweaks. */
    fun luminance(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Pegtop soft light: continuous (no branch seam) and gentle, so the sheen tints without washing out. */
    fun softLight(base: Float, blend: Float): Float = (1f - 2f * blend) * base * base + 2f * blend * base

    fun screen(base: Float, blend: Float): Float = 1f - (1f - base) * (1f - blend)

    /** Fully saturated hue ([hue] in turns, any real value) for each channel: 0 = red, 1 = green, 2 = blue. */
    fun hueChannel(hue: Float, channel: Int): Float {
        val h = hue - kotlin.math.floor(hue)
        val h6 = h * 6f
        return when (channel) {
            0 -> clamp01(abs(h6 - 3f) - 1f)
            1 -> clamp01(2f - abs(h6 - 2f))
            else -> clamp01(2f - abs(h6 - 4f))
        }
    }
}
