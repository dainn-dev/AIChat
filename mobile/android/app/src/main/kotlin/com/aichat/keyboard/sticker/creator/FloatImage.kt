/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.roundToInt

/**
 * Premultiplied RGBA as float planes (0..1). Layers are composited premultiplied so soft cutout edges blend onto the
 * border without dark fringes; converted back to non-premultiplied ints only once, at the end.
 */
internal class FloatImage(val width: Int, val height: Int) {
    val r = FloatArray(width * height)
    val g = FloatArray(width * height)
    val b = FloatArray(width * height)
    val a = FloatArray(width * height)

    /** Paints a solid color with per-pixel [coverage] (0..1) times [opacity] over this image. */
    fun fillOver(coverage: FloatArray, cr: Float, cg: Float, cb: Float, opacity: Float = 1f) {
        for (i in a.indices) {
            val sa = coverage[i] * opacity
            if (sa <= 0f) continue
            val k = 1f - sa
            r[i] = cr * sa + r[i] * k
            g[i] = cg * sa + g[i] * k
            b[i] = cb * sa + b[i] * k
            a[i] = sa + a[i] * k
        }
    }

    /** Source-over: draws [src] (same size) over this image. */
    fun drawOver(src: FloatImage) {
        for (i in a.indices) {
            val sa = src.a[i]
            if (sa <= 0f) continue
            val k = 1f - sa
            r[i] = src.r[i] + r[i] * k
            g[i] = src.g[i] + g[i] * k
            b[i] = src.b[i] + b[i] * k
            a[i] = sa + a[i] * k
        }
    }

    /** Non-premultiplied ARGB ints; pixels whose alpha rounds to 0 become fully transparent (0). */
    fun toPixels(): StickerPixels {
        val out = IntArray(width * height)
        for (i in out.indices) {
            val alpha = a[i]
            val ai = (ColorMath.clamp01(alpha) * 255f).roundToInt()
            if (ai == 0) continue
            val inv = 1f / alpha
            val ri = (ColorMath.clamp01(r[i] * inv) * 255f).roundToInt()
            val gi = (ColorMath.clamp01(g[i] * inv) * 255f).roundToInt()
            val bi = (ColorMath.clamp01(b[i] * inv) * 255f).roundToInt()
            out[i] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return StickerPixels(width, height, out)
    }

    companion object {
        fun of(pixels: StickerPixels): FloatImage {
            val img = FloatImage(pixels.width, pixels.height)
            val src = pixels.argb
            for (i in src.indices) {
                val c = src[i]
                val alpha = (c ushr 24) / 255f
                if (alpha == 0f) continue
                img.a[i] = alpha
                img.r[i] = ((c shr 16) and 0xFF) / 255f * alpha
                img.g[i] = ((c shr 8) and 0xFF) / 255f * alpha
                img.b[i] = (c and 0xFF) / 255f * alpha
            }
            return img
        }
    }
}
