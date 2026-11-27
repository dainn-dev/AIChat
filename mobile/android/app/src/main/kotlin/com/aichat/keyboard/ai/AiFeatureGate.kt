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

    /**
     * The chat reader only feeds AI reply suggestions, so its entry goes with the AI, except while the reader is
     * still enabled: the user must always be able to review and revoke it.
     */
    fun showChatReaderEntry(enabled: Boolean, readerEnabled: Boolean): Boolean = enabled || readerEnabled

    /** The AI state the smartbar may draw: always [AiAssistState.Closed] while the AI is off. */
    fun panelState(enabled: Boolean, state: AiAssistState): AiAssistState =
        if (enabled) state else AiAssistState.Closed

    /** Turning the AI off while its panel is open closes the panel. */
    fun shouldCloseAssist(enabled: Boolean, state: AiAssistState): Boolean =
        !enabled && state != AiAssistState.Closed
}
