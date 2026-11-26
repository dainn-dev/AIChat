/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import android.content.Context

/** Sizes shared by the segmenter, the composer and the effects. */
object CutoutSpec {
    /** Side of the square sticker, same as the built-in and imported stickers (`StickerRenderer.SIZE_PX`). */
    const val OUTPUT_PX = 512

    /** Transparent margin kept around the subject on each side, as a share of [OUTPUT_PX], so outlines and shadows fit. */
    const val MARGIN_FRACTION = 0.08f

    /** A mask value at or above this is inside the subject (used for hit tests and bounds). */
    const val MASK_THRESHOLD = 0.5f

    /** The source photo is downscaled so its longer side is at most this before segmentation (memory bound). */
    const val MAX_INPUT_PX = 1600
}

/**
 * One subject found in the photo. [confidence] covers only its bounding box ([left], [top], [width], [height], in
 * source image pixels), row-major, values 0..1 (1 = surely the subject).
 */
class DetectedSubject(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val confidence: FloatArray,
) {
    init {
        require(width > 0 && height > 0 && confidence.size == width * height)
    }

    /** Mask value at source image coordinates ([x], [y]); 0 outside the bounding box. */
    fun confidenceAt(x: Int, y: Int): Float {
        val lx = x - left
        val ly = y - top
        if (lx !in 0 until width || ly !in 0 until height) return 0f
        return confidence[ly * width + lx]
    }
}

/** Subjects found in an image of [imageWidth] x [imageHeight]; empty when nothing stands out. */
class SegmentationResult(
    val imageWidth: Int,
    val imageHeight: Int,
    val subjects: List<DetectedSubject>,
)

sealed interface SegmenterAvailability {
    /** The model is on the device; [SubjectSegmenter.segment] works offline. */
    data object Ready : SegmenterAvailability

    /** Google Play services can fetch the model ([SubjectSegmenter.ensureReady]); needs network once. */
    data object NeedsDownload : SegmenterAvailability

    /** No Google Play services (or too old): hide "create sticker" and keep plain image import. */
    data object Unsupported : SegmenterAvailability
}

/** On-device subject segmentation. Photos never leave the device. */
interface SubjectSegmenter {
    suspend fun availability(): SegmenterAvailability

    /** Downloads the model if needed and waits for it; true when [segment] can run. */
    suspend fun ensureReady(): Boolean

    /** Finds the subjects in [source] (already rotated upright, longer side at most [CutoutSpec.MAX_INPUT_PX]). */
    suspend fun segment(source: StickerPixels): SegmentationResult

    fun close()
}

/** Creates the app's [SubjectSegmenter] (ML Kit Subject Segmentation through Google Play services). */
object SubjectSegmenters {
    fun create(context: Context): SubjectSegmenter = MlKitSubjectSegmenter(context)
}
