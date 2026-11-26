/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

/** Turns segmentation masks into a cut-out sticker. Pure Kotlin (JVM-testable). */
object CutoutComposer {
    /**
     * Index (into [SegmentationResult.subjects]) of the subject under source image point ([x], [y]), or null when the
     * point is background. When masks overlap, the subject with the higher confidence there wins.
     */
    fun subjectAt(result: SegmentationResult, x: Int, y: Int): Int? = TODO("Implemented by the segmentation workstream")

    /**
     * Cuts the [selected] subjects out of [source] (alpha = mask confidence, so edges stay soft), crops to the union of
     * their masks, scales to fit [outputPx] minus [marginFraction] on each side (never upscaling more than needed to
     * fill), and centers the result on a transparent [outputPx] square. Empty [selected] → all subjects.
     */
    fun compose(
        source: StickerPixels,
        result: SegmentationResult,
        selected: Set<Int>,
        outputPx: Int = CutoutSpec.OUTPUT_PX,
        marginFraction: Float = CutoutSpec.MARGIN_FRACTION,
    ): StickerPixels = TODO("Implemented by the segmentation workstream")
}
