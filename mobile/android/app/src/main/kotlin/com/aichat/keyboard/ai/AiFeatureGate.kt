/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

/**
 * The "Trợ lý AI" setting (`prefs.aichatAi.enabled`): the AI features are opt-in. Off, the keyboard has no AI button
 * and the settings home hides the chat reader entry. Pure, so the rules are unit-tested.
 */
object AiFeatureGate {
    /** The AI starts off, also for existing installs (no stored value). */
    const val DEFAULT_ENABLED = false

    fun showAiButton(enabled: Boolean): Boolean = enabled

    /** The chat reader only feeds AI reply suggestions, so its entry goes with the AI. */
    fun showChatReaderEntry(enabled: Boolean): Boolean = enabled

    /** Turning the AI off while its panel is open closes the panel. */
    fun shouldCloseAssist(enabled: Boolean, state: AiAssistState): Boolean =
        !enabled && state != AiAssistState.Closed
}
