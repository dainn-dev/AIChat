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
    fun `ai button follows the setting`() {
        assertTrue(AiFeatureGate.showAiButton(enabled = true))
        assertFalse(AiFeatureGate.showAiButton(enabled = false))
    }

    @Test
    fun `chat reader entry shows with ai on or while the reader is still enabled`() {
        assertTrue(AiFeatureGate.showChatReaderEntry(enabled = true, readerEnabled = false))
        assertFalse(AiFeatureGate.showChatReaderEntry(enabled = false, readerEnabled = false))
        // A reader the user turned on earlier must stay reviewable and revocable after the AI is switched off.
        assertTrue(AiFeatureGate.showChatReaderEntry(enabled = false, readerEnabled = true))
    }

    @Test
    fun `ai panel is never shown while ai is off`() {
        assertTrue(AiFeatureGate.panelState(enabled = true, state = AiAssistState.Ready) == AiAssistState.Ready)
        assertTrue(AiFeatureGate.panelState(enabled = false, state = AiAssistState.Ready) == AiAssistState.Closed)
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
