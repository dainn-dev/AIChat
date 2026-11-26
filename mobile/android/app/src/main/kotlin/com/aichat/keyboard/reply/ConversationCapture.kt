/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.reply

import com.aichat.keyboard.core.network.model.ChatMessage

/**
 * The chat the user is replying to, as read by a [ConversationProvider]. Oldest message first; [platform] is the
 * chat app id (`messenger`, `zalo`, ...) and [contactLabel] the chat partner's display name, both optional. They
 * let the backend scope memories per contact; without both, nothing is stored server-side.
 */
data class CapturedConversation(
    val messages: List<ChatMessage>,
    val platform: String? = null,
    val contactLabel: String? = null,
) {
    init {
        require(messages.isNotEmpty()) { "A captured conversation has at least one message" }
    }

    companion object {
        /** `@ArrayMaxSize(100)` on the backend's reply DTO: keep only the most recent messages. */
        const val MAX_MESSAGES = 100
    }
}

/** Outcome of one capture attempt. */
sealed interface CaptureResult {
    data class Captured(val conversation: CapturedConversation) : CaptureResult

    /** This source cannot provide a conversation right now; the next provider is tried. */
    data class Unavailable(val reason: UnavailableReason) : CaptureResult
}

enum class UnavailableReason {
    /** The user has not enabled this source (e.g. the accessibility service is off). */
    NOT_ENABLED,

    /** The foreground app is not one this source can read. */
    UNSUPPORTED_APP,

    /** The source is enabled but found no messages. */
    NOTHING_FOUND,

    /** The source is enabled in settings but not running (e.g. the accessibility service is not connected). */
    NOT_CONNECTED,
}

/**
 * A way to read the conversation behind the focused text field. Implementations must never throw (return
 * [CaptureResult.Unavailable] instead), must not block the main thread, and must never read password fields.
 */
interface ConversationProvider {
    /** Stable id for logs and settings, e.g. `accessibility`, `clipboard`. */
    val id: String

    /**
     * Whether to try this provider after the earlier ones gave [earlier] (provider id to reason). A fallback such
     * as the clipboard uses it to stay out of the way when an earlier, better source should have worked.
     */
    fun shouldTry(earlier: Map<String, UnavailableReason>): Boolean = true

    suspend fun capture(): CaptureResult
}

/**
 * Tries [providers] in order and returns the first conversation found, or the reasons every tried provider gave.
 * A provider whose [ConversationProvider.shouldTry] is false is skipped and gives no reason.
 */
class ConversationCapturer(private val providers: List<ConversationProvider>) {
    sealed interface Outcome {
        data class Found(val providerId: String, val conversation: CapturedConversation) : Outcome
        data class NotFound(val reasons: Map<String, UnavailableReason>) : Outcome
    }

    suspend fun capture(): Outcome {
        val reasons = linkedMapOf<String, UnavailableReason>()
        for (provider in providers) {
            if (!provider.shouldTry(reasons)) continue
            val result = try {
                provider.capture()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND)
            }
            when (result) {
                is CaptureResult.Captured -> return Outcome.Found(provider.id, result.conversation.trimmed())
                is CaptureResult.Unavailable -> reasons[provider.id] = result.reason
            }
        }
        return Outcome.NotFound(reasons)
    }

    private fun CapturedConversation.trimmed(): CapturedConversation =
        if (messages.size <= CapturedConversation.MAX_MESSAGES) {
            this
        } else {
            copy(messages = messages.takeLast(CapturedConversation.MAX_MESSAGES))
        }
}
