/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

/**
 * An ARGB_8888 image as plain ints (row-major, `argb[y * width + x]`), with no Android types so the cutout and effect
 * code runs in JVM unit tests. Converted to and from `Bitmap` only at the edges (see `StickerBitmaps`).
 */
class StickerPixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "Empty image: ${width}x$height" }
        require(argb.size == width * height) { "Expected ${width * height} pixels, got ${argb.size}" }
    }

    operator fun get(x: Int, y: Int): Int = argb[y * width + x]

    operator fun set(x: Int, y: Int, color: Int) {
        argb[y * width + x] = color
    }

    fun copy(): StickerPixels = StickerPixels(width, height, argb.copyOf())

    companion object {
        fun transparent(width: Int, height: Int): StickerPixels = StickerPixels(width, height, IntArray(width * height))
    }
}
