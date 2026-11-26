/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.reply

import android.content.Context

/**
 * Uses the message the user copied (the clipboard) as a one-message conversation from the chat partner.
 *
 * STUB: always [UnavailableReason.NOTHING_FOUND] until the clipboard capture lands (workstream C).
 */
class ClipboardConversationProvider(@Suppress("unused") private val context: Context) : ConversationProvider {
    override val id = ID

    override suspend fun capture(): CaptureResult = CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND)

    companion object {
        const val ID = "clipboard"
    }
}
