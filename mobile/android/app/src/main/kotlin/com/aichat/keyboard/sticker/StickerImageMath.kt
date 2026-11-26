package com.aichat.keyboard.sticker

import kotlin.math.max
import kotlin.math.roundToInt

/** Size arithmetic for importing gallery images as stickers. Pure Kotlin so it is unit-tested. */
object StickerImageMath {
    /**
     * The `BitmapFactory.Options.inSampleSize` for decoding a [width] x [height] image: the largest power of two
     * that still leaves both sides at least [target], so the decode is cheap but loses no needed detail.
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }

    /** The size that fits [width] x [height] inside a [target] square, keeping the aspect ratio, never upscaling. */
    fun fitInside(width: Int, height: Int, target: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= target) return width to height
        val scale = target.toFloat() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }
}
