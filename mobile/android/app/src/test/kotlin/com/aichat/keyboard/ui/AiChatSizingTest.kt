package com.aichat.keyboard.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class AiChatSizingTest {
    private val density = 3.5f // the 1260 px wide test phone (360 dp)

    @Test
    fun `a phone row is the iOS share of the screen width`() {
        // 0.136 * 1260 = 171.36 px, the row pitch measured on iOS.
        assertEquals(171.36f, AiChatSizing.portraitRowHeightPx(widthPx = 1260, density = density), 0.01f)
    }

    @Test
    fun `rows never get shorter than the minimum on narrow screens`() {
        // 0.136 * 280 dp = 38 dp < 44 dp minimum.
        assertEquals(44f * density, AiChatSizing.portraitRowHeightPx(widthPx = (280 * density).toInt(), density = density))
    }

    @Test
    fun `rows stop growing on tablets`() {
        // 0.136 * 800 dp = 109 dp > 64 dp maximum.
        assertEquals(64f * density, AiChatSizing.portraitRowHeightPx(widthPx = (800 * density).toInt(), density = density))
    }




    @Test
    fun `the bottom strip follows the screen width within its bounds`() {
        assertEquals(43.2f, AiChatSizing.bottomBarHeightDp(widthDp = 360f), 0.01f)
        assertEquals(36f, AiChatSizing.bottomBarHeightDp(widthDp = 200f))
        assertEquals(56f, AiChatSizing.bottomBarHeightDp(widthDp = 900f))
    }

    @Test
    fun `suggestions-only smartbar collapses when empty and opens for suggestions or the AI chips`() {
        assertEquals(false, AiChatSizing.shouldShowSmartbar(enabled = true, suggestionsOnly = true, hasSuggestions = false, aiOpen = false))
        assertEquals(true, AiChatSizing.shouldShowSmartbar(enabled = true, suggestionsOnly = true, hasSuggestions = true, aiOpen = false))
        assertEquals(true, AiChatSizing.shouldShowSmartbar(enabled = true, suggestionsOnly = true, hasSuggestions = false, aiOpen = true))
    }

    @Test
    fun `quick-action layouts always show and a disabled smartbar never does`() {
        assertEquals(true, AiChatSizing.shouldShowSmartbar(enabled = true, suggestionsOnly = false, hasSuggestions = false, aiOpen = false))
        assertEquals(false, AiChatSizing.shouldShowSmartbar(enabled = false, suggestionsOnly = true, hasSuggestions = true, aiOpen = true))
    }
}
