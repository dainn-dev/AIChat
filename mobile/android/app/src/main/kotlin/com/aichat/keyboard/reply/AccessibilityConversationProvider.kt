/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.reply

import android.content.Context

/**
 * Reads the visible chat of a supported messenger through the AIChat accessibility service.
 *
 * STUB: always [UnavailableReason.NOT_ENABLED] until the accessibility capture lands (workstream A).
 */
class AccessibilityConversationProvider(@Suppress("unused") private val context: Context) : ConversationProvider {
    override val id = ID

    override suspend fun capture(): CaptureResult = CaptureResult.Unavailable(UnavailableReason.NOT_ENABLED)

    companion object {
        const val ID = "accessibility"
    }
}
