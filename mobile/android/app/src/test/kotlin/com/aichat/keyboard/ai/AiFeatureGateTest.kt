/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiFeatureGateTest {
    @Test
    fun `ai is off by default`() {
        assertFalse(AiFeatureGate.DEFAULT_ENABLED)
    }

    @Test
    fun `ai button and chat reader entry follow the setting`() {
        assertTrue(AiFeatureGate.showAiButton(enabled = true))
        assertFalse(AiFeatureGate.showAiButton(enabled = false))
        assertTrue(AiFeatureGate.showChatReaderEntry(enabled = true))
        assertFalse(AiFeatureGate.showChatReaderEntry(enabled = false))
    }

    @Test
    fun `turning ai off closes an open panel`() {
        assertTrue(AiFeatureGate.shouldCloseAssist(enabled = false, state = AiAssistState.Ready))
        assertTrue(AiFeatureGate.shouldCloseAssist(enabled = false, state = AiAssistState.ChoosingReplyTone))
    }

    @Test
    fun `nothing to close when the panel is closed or ai is on`() {
        assertFalse(AiFeatureGate.shouldCloseAssist(enabled = false, state = AiAssistState.Closed))
        assertFalse(AiFeatureGate.shouldCloseAssist(enabled = true, state = AiAssistState.Ready))
    }
}
