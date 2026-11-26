/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

/** iOS-like sticker looks. Every effect except [ORIGINAL] also draws the white die-cut outline. */
enum class StickerEffect {
    /** The cutout as is. */
    ORIGINAL,

    /** White die-cut border and a soft drop shadow. */
    OUTLINE,

    /** Flat posterized colors with dark ink edges, plus the outline. */
    COMIC,

    /** Inflated, glossy 3D look (shading from the mask's distance to the edge), plus the outline. */
    PUFFY,

    /** Holographic rainbow sheen over the subject, plus the outline. */
    SHINY,
}

/** Applies a [StickerEffect] to a cutout from [CutoutComposer.compose]. Pure Kotlin (JVM-testable). */
object StickerEffects {
    /**
     * Returns a new image of the same size; [cutout] is not modified. The cutout's transparent margin
     * ([CutoutSpec.MARGIN_FRACTION]) is where the outline and shadow go; nothing is drawn outside the canvas.
     */
    fun apply(effect: StickerEffect, cutout: StickerPixels): StickerPixels = TODO("Implemented by the effects workstream")
}
