/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.schedule

import android.content.Context
import com.aichat.keyboard.ai.AiEditorPort
import com.aichat.keyboard.ai.FieldRead
import com.aichat.keyboard.capture.ChatCaptureService
import com.aichat.keyboard.capture.ChatRecipient
import com.aichat.keyboard.capture.ChatTranscriptParser
import com.aichat.keyboard.capture.SnapshotOutcome
import com.aichat.keyboard.schedule.ui.ScheduleMessageActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The bottom bar's schedule key: reads what the user typed and who the open chat is with, then opens the
 * "Hẹn giờ gửi" popup with both filled in. Either may be missing; the popup says so and lets the user type the
 * message there.
 */
internal object ScheduleLauncher {
    /** Reading a chat window takes tens of milliseconds; give up well before the user notices. */
    private const val SNAPSHOT_TIMEOUT_MS = 1_500L

    suspend fun open(context: Context, editor: AiEditorPort) {
        val draft = when (val read = if (editor.isAvailable()) editor.readAllText() else FieldRead.Unreadable) {
            is FieldRead.Text -> read.text.take(ScheduleDraft.MAX_CHARS)
            else -> ""
        }
        ScheduleMessageActivity.launch(context, draft, readRecipient())
    }

    private suspend fun readRecipient(): ChatRecipient? = try {
        when (val outcome = withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { ChatCaptureService.requestSnapshot() }) {
            is SnapshotOutcome.Snapshot -> ChatTranscriptParser.recipient(outcome.snapshot)
            else -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        null
    }
}
