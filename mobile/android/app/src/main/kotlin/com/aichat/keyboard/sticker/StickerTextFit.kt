package com.aichat.keyboard.sticker

/** Picks the font size of a text sticker. Pure Kotlin so the sizing rule is unit-tested. */
object StickerTextFit {
    /**
     * The largest font size, between [minSize] and [maxSize], at which every line fits in a square [box].
     *
     * @param lineWidthsPerUnit width of each line at a font size of 1 (text width scales linearly with size).
     * @param lineHeightPerUnit height of one line at a font size of 1, including line spacing.
     */
    fun fontSize(
        lineWidthsPerUnit: List<Float>,
        lineHeightPerUnit: Float,
        box: Float,
        maxSize: Float,
        minSize: Float,
    ): Float {
        val widest = lineWidthsPerUnit.maxOrNull() ?: 0f
        val byWidth = if (widest > 0f) box / widest else maxSize
        val blockHeight = lineWidthsPerUnit.size * lineHeightPerUnit
        val byHeight = if (blockHeight > 0f) box / blockHeight else maxSize
        return minOf(maxSize, byWidth, byHeight).coerceAtLeast(minSize)
    }
}
