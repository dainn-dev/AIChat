package com.aichat.keyboard.vi.glide

import dev.patrickgold.florisboard.app.FlorisPreferenceModelImpl
import dev.patrickgold.florisboard.ime.text.gestures.SwipeAction
import kotlin.test.Test
import kotlin.test.assertEquals

// AIChat: pins the Gboard-like defaults (glide owns letter swipes).
class GestureDefaultsTest {
    private val prefs = FlorisPreferenceModelImpl()

    @Test fun glideEnabledByDefault() = assertEquals(true, prefs.glide.enabled.default)
    @Test fun letterSwipesAreNoAction() {
        assertEquals(SwipeAction.NO_ACTION, prefs.gestures.swipeUp.default)
        assertEquals(SwipeAction.NO_ACTION, prefs.gestures.swipeDown.default)
        assertEquals(SwipeAction.NO_ACTION, prefs.gestures.swipeLeft.default)
        assertEquals(SwipeAction.NO_ACTION, prefs.gestures.swipeRight.default)
    }
    @Test fun deleteKeySwipeDeletesWords() =
        assertEquals(SwipeAction.DELETE_WORDS_PRECISELY, prefs.gestures.deleteKeySwipeLeft.default)
    @Test fun spaceBarSwipesStillMoveCursor() {
        assertEquals(SwipeAction.MOVE_CURSOR_LEFT, prefs.gestures.spaceBarSwipeLeft.default)
        assertEquals(SwipeAction.MOVE_CURSOR_RIGHT, prefs.gestures.spaceBarSwipeRight.default)
    }
}
