/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.reply

import android.content.ClipData
import android.content.Context
import android.view.inputmethod.InputConnection
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.clipboardManager
import dev.patrickgold.florisboard.ime.clipboard.provider.ClipboardItem
import dev.patrickgold.florisboard.ime.clipboard.provider.ItemType

/**
 * Uses the message the user just copied (the clipboard) as a one-message conversation from the chat partner.
 *
 * The keyboard's own clipboard record (FlorisBoard's clipboard manager, fed by the system's primary-clip listener)
 * is read first; the system clipboard is the fallback for copies the keyboard missed. Android 10+ lets only the
 * focused IME read the clipboard in the background, which is the case when the user taps "Suggest replies".
 * Which clip counts is decided by [ClipboardReplyPolicy] (recent, plain text, not sensitive, not the user's own
 * draft). The clip content is never logged.
 */
class ClipboardConversationProvider(private val context: Context) : ConversationProvider {
    override val id = ID

    override suspend fun capture(): CaptureResult {
        val clip = ClipboardReplyPolicy.pick(florisClip(), systemClip())
        val message = ClipboardReplyPolicy.messageFrom(clip, System.currentTimeMillis(), currentFieldText())
            ?: return CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND)
        return CaptureResult.Captured(CapturedConversation(listOf(ChatMessage(MessageSender.THEM, message))))
    }

    private fun florisClip(): ClipSnapshot? = runCatching {
        val clipboardManager by context.clipboardManager()
        clipboardManager.primaryClip?.toSnapshot()
    }.getOrNull()

    private fun systemClip(): ClipSnapshot? = runCatching {
        val manager = context.getSystemService(android.content.ClipboardManager::class.java)
        manager?.primaryClip?.toSnapshot()
    }.getOrNull()

    /** The whole focused field, to skip a clip that is just the user's own draft. Null when unreadable. */
    private fun currentFieldText(): String? = runCatching {
        val ic: InputConnection = FlorisImeService.currentInputConnection() ?: return@runCatching null
        val before = ic.getTextBeforeCursor(FIELD_READ_LIMIT, 0)
        val after = ic.getTextAfterCursor(FIELD_READ_LIMIT, 0)
        if (before == null && after == null) return@runCatching null
        before?.toString().orEmpty() + ic.getSelectedText(0)?.toString().orEmpty() + after?.toString().orEmpty()
    }.getOrNull()

    private fun ClipboardItem.toSnapshot() = ClipSnapshot(
        text = text,
        timestampMs = creationTimestampMs,
        isSensitive = isSensitive,
        isPlainText = type == ItemType.TEXT && uri == null,
    )

    private fun ClipData.toSnapshot(): ClipSnapshot? {
        if (itemCount == 0) return null
        val item = getItemAt(0)
        val description = description
        return ClipSnapshot(
            text = item.text?.toString(),
            // Set by the clipboard service when the clip was copied, in System.currentTimeMillis() time.
            timestampMs = description?.timestamp ?: 0L,
            // ClipDescription.EXTRA_IS_SENSITIVE (API 33); apps on older releases may set the same key.
            isSensitive = description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true,
            isPlainText = item.uri == null && item.text != null && description?.hasMimeType("text/*") == true,
        )
    }

    companion object {
        const val ID = "clipboard"
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        /** Enough to compare with any clip we would accept. */
        private const val FIELD_READ_LIMIT = ClipboardReplyPolicy.MAX_MESSAGE_LENGTH + 1
    }
}
