package com.aichat.keyboard.ui

/**
 * Keyboard proportions taken from the iOS keyboard, as shares of the screen width (measured on an iOS screenshot):
 * a key row repeats every 0.136 W and the globe / mic strip below the keys takes 0.12 W (slimmer than iOS, with small icons).
 *
 * Tying the sizes to the width (not the height, as upstream FlorisBoard does) keeps the keys the same shape on
 * every phone. The bounds stop tiny screens from getting untappable keys and tablets from getting huge ones.
 * Pure Kotlin, unit-tested.
 */
object AiChatSizing {
    const val ROW_HEIGHT_PER_WIDTH = 0.136f
    const val MIN_ROW_HEIGHT_DP = 44f
    const val MAX_ROW_HEIGHT_DP = 64f

    const val BOTTOM_BAR_PER_WIDTH = 0.12f
    const val MIN_BOTTOM_BAR_DP = 36f
    const val MAX_BOTTOM_BAR_DP = 56f

    /** The base row height in px for portrait (key height plus the vertical key spacing). */
    fun portraitRowHeightPx(widthPx: Int, density: Float): Float =
        (widthPx * ROW_HEIGHT_PER_WIDTH).coerceIn(MIN_ROW_HEIGHT_DP * density, MAX_ROW_HEIGHT_DP * density)

    /** Height of [AiChatImeBottomBar], in dp. */
    fun bottomBarHeightDp(widthDp: Float): Float =
        (widthDp * BOTTOM_BAR_PER_WIDTH).coerceIn(MIN_BOTTOM_BAR_DP, MAX_BOTTOM_BAR_DP)

    /**
     * Whether to show the smartbar. The suggestions-only layout (the AIChat default) collapses when it has nothing
     * to show, and opens for word suggestions or for the AI chips (the AI button itself lives in the bottom strip).
     */
    fun shouldShowSmartbar(enabled: Boolean, suggestionsOnly: Boolean, hasSuggestions: Boolean, aiOpen: Boolean): Boolean =
        enabled && (!suggestionsOnly || hasSuggestions || aiOpen)
}
