/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.reply

import android.content.Context
import com.aichat.keyboard.capture.ChatCaptureService
import com.aichat.keyboard.capture.ChatTranscriptParser
import com.aichat.keyboard.capture.ChatWindowSnapshot
import com.aichat.keyboard.capture.SnapshotOutcome
import com.aichat.keyboard.capture.SupportedMessenger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reads the visible chat of the app in front through [ChatCaptureService], only when the user asks for reply
 * suggestions. Never throws: every failure maps to an [UnavailableReason]. When an app other than a
 * [SupportedMessenger] shows nothing the parser recognises as a chat, that is [UnavailableReason.UNSUPPORTED_APP], so
 * the clipboard may stand in; in a known messenger it stays [UnavailableReason.NOTHING_FOUND].
 */
class AccessibilityConversationProvider internal constructor(
    private val isServiceEnabled: () -> Boolean,
    private val snapshotSource: suspend () -> SnapshotOutcome,
    private val parse: (ChatWindowSnapshot) -> CapturedConversation? = ChatTranscriptParser::parse,
) : ConversationProvider {
    constructor(context: Context) : this(
        isServiceEnabled = { ChatCaptureService.isEnabled(context.applicationContext) },
        snapshotSource = { ChatCaptureService.requestSnapshot() },
    )

    override val id = ID

    override suspend fun capture(): CaptureResult {
        val enabled = try {
            isServiceEnabled()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        }
        if (!enabled) return unavailable(UnavailableReason.NOT_ENABLED)

        val outcome = try {
            withTimeoutOrNull(SNAPSHOT_TIMEOUT_MS) { snapshotSource() } ?: SnapshotOutcome.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            SnapshotOutcome.Failed
        }

        return when (outcome) {
            // Enabled in settings but not connected: the user has to switch it off and on again.
            SnapshotOutcome.NotRunning -> unavailable(UnavailableReason.NOT_CONNECTED)
            SnapshotOutcome.UnsupportedApp -> unavailable(UnavailableReason.UNSUPPORTED_APP)
            SnapshotOutcome.Failed -> unavailable(UnavailableReason.NOTHING_FOUND)
            is SnapshotOutcome.Snapshot -> {
                val conversation = try {
                    parse(outcome.snapshot)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    null
                }
                when {
                    conversation != null -> CaptureResult.Captured(conversation)
                    SupportedMessenger.fromPackage(outcome.snapshot.packageName) == null ->
                        unavailable(UnavailableReason.UNSUPPORTED_APP)
                    else -> unavailable(UnavailableReason.NOTHING_FOUND)
                }
            }
        }
    }

    private fun unavailable(reason: UnavailableReason) = CaptureResult.Unavailable(reason)

    companion object {
        const val ID = "accessibility"

        /** Reading a chat window takes tens of milliseconds; give up well before the user notices. */
        internal const val SNAPSHOT_TIMEOUT_MS = 1_500L
    }
}
