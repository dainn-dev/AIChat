/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.reply.CapturedConversation
import com.aichat.keyboard.reply.ConversationCapturer
import com.aichat.keyboard.reply.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InterruptedIOException

/** What the AI button can do with the text in the focused field. */
sealed interface AiAction {
    data class Rewrite(val tone: Tone) : AiAction

    /** [targetLang] is a backend language code, e.g. `en` or `vi`. */
    data class Translate(val targetLang: String) : AiAction

    /** Suggest replies to the chat behind the field (see [com.aichat.keyboard.reply.ConversationProvider]). */
    data class Reply(val tone: Tone) : AiAction
}

/** Why an AI action did not change the field. Each one maps to a localized message in the smartbar. */
enum class AiFailure {
    /** No editor, or a field where text must not leave the device (password). */
    UNAVAILABLE,
    EMPTY_TEXT,
    TOO_LONG,
    NOT_SIGNED_IN,
    QUOTA,
    NETWORK,

    /** The request timed out: online, but the AI took too long. */
    TIMEOUT,
    SERVER,

    /** The user edited the field while the request was running; the stale result is not applied. */
    TEXT_CHANGED,

    /** No provider could read the chat to reply to. */
    NO_CONVERSATION,
}

sealed interface AiAssistState {
    /** The smartbar shows its normal suggestions. */
    data object Closed : AiAssistState

    /** The action chips are shown. */
    data object Ready : AiAssistState

    data class Loading(val action: AiAction) : AiAssistState

    /** The field was replaced; [original] is kept for undo. */
    data class Done(val original: String) : AiAssistState

    /** Reply flow: the tone chips (Flirty, Funny, ...) are shown. */
    data object ChoosingReplyTone : AiAssistState

    /** Reply flow: suggested replies; picking one fills the field. */
    data class Suggestions(val replies: List<String>) : AiAssistState

    /**
     * [captureDisabled] is set with [AiFailure.NO_CONVERSATION] when a chat reader is switched off (e.g. the
     * accessibility service), so the UI can offer to turn it on.
     */
    data class Failed(val reason: AiFailure, val captureDisabled: Boolean = false) : AiAssistState
}

/** The focused text field, as the AI assistant sees it. */
interface AiEditorPort {
    /** False when there is no editor or the field is sensitive (password). */
    fun isAvailable(): Boolean

    /** The whole field text, or null when it cannot be read. */
    fun readAllText(): String?

    fun replaceAllText(text: String): Boolean
}

/** The backend calls behind the AI button. */
interface AiTextBackend {
    /** Rewrite or translate [text]; returns the new text. */
    suspend fun run(action: AiAction, text: String): ApiResult<String>

    /** Suggested replies to [conversation] in [tone], best first. */
    suspend fun reply(conversation: CapturedConversation, tone: Tone): ApiResult<List<String>>
}

/**
 * State machine behind the smartbar AI button: open the chips, run one action at a time on the whole field,
 * replace the field with the result, and offer undo. Calls never throw; every failure becomes [AiAssistState.Failed].
 */
class AiAssistController(
    private val backend: AiTextBackend,
    private val editor: AiEditorPort,
    private val scope: CoroutineScope,
    private val capturer: ConversationCapturer = ConversationCapturer(emptyList()),
) {
    private val _state = MutableStateFlow<AiAssistState>(AiAssistState.Closed)
    val state: StateFlow<AiAssistState> = _state.asStateFlow()

    private var job: Job? = null

    fun toggle() {
        if (_state.value == AiAssistState.Closed) _state.value = AiAssistState.Ready else reset()
    }

    /** Back to the chips after a result or an error. */
    fun dismissMessage() {
        val s = _state.value
        if (s is AiAssistState.Done || s is AiAssistState.Failed || s is AiAssistState.Suggestions ||
            s == AiAssistState.ChoosingReplyTone
        ) {
            _state.value = AiAssistState.Ready
        }
    }

    fun run(action: AiAction) {
        if (action is AiAction.Reply) return requestReplies(action.tone)
        if (_state.value is AiAssistState.Loading) return
        val original = if (editor.isAvailable()) editor.readAllText() else null
        if (original == null) return fail(AiFailure.UNAVAILABLE)
        val input = original.trim()
        when {
            input.isEmpty() -> return fail(AiFailure.EMPTY_TEXT)
            input.length > MAX_TEXT_LENGTH -> return fail(AiFailure.TOO_LONG)
        }
        _state.value = AiAssistState.Loading(action)
        job = scope.launch {
            val result = backend.run(action, input)
            _state.value = when (result) {
                is ApiResult.Failure -> AiAssistState.Failed(result.error.toFailure())
                is ApiResult.Success -> apply(original, result.value)
            }
        }
    }

    /** Opens the reply tone chips. */
    fun startReply() {
        if (_state.value !is AiAssistState.Loading) _state.value = AiAssistState.ChoosingReplyTone
    }

    /** Reads the chat through the providers, then asks the backend for replies in [tone]. */
    fun requestReplies(tone: Tone) {
        if (_state.value is AiAssistState.Loading) return
        if (!editor.isAvailable()) return fail(AiFailure.UNAVAILABLE)
        val action = AiAction.Reply(tone)
        _state.value = AiAssistState.Loading(action)
        job = scope.launch {
            _state.value = when (val outcome = capturer.capture()) {
                is ConversationCapturer.Outcome.NotFound -> AiAssistState.Failed(
                    AiFailure.NO_CONVERSATION,
                    captureDisabled = UnavailableReason.NOT_ENABLED in outcome.reasons.values,
                )
                is ConversationCapturer.Outcome.Found -> when (val result = backend.reply(outcome.conversation, tone)) {
                    is ApiResult.Failure -> AiAssistState.Failed(result.error.toFailure())
                    is ApiResult.Success -> result.value.filter { it.isNotBlank() }.let { replies ->
                        if (replies.isEmpty()) AiAssistState.Failed(AiFailure.SERVER) else AiAssistState.Suggestions(replies)
                    }
                }
            }
        }
    }

    /** Fills the field with [reply] (replacing any draft, which undo restores). */
    fun pickSuggestion(reply: String) {
        if (_state.value !is AiAssistState.Suggestions) return
        val original = if (editor.isAvailable()) editor.readAllText() else null
        if (original == null) return fail(AiFailure.UNAVAILABLE)
        _state.value = if (editor.replaceAllText(reply)) {
            AiAssistState.Done(original)
        } else {
            AiAssistState.Failed(AiFailure.UNAVAILABLE)
        }
    }

    fun undo() {
        val done = _state.value as? AiAssistState.Done ?: return
        editor.replaceAllText(done.original)
        _state.value = AiAssistState.Ready
    }

    /** Cancels any request and closes the chips (new field, keyboard hidden). */
    fun reset() {
        job?.cancel()
        job = null
        _state.value = AiAssistState.Closed
    }

    private fun apply(original: String, newText: String): AiAssistState {
        if (editor.readAllText() != original) return AiAssistState.Failed(AiFailure.TEXT_CHANGED)
        return if (editor.replaceAllText(newText)) {
            AiAssistState.Done(original)
        } else {
            AiAssistState.Failed(AiFailure.UNAVAILABLE)
        }
    }

    private fun fail(reason: AiFailure) {
        _state.value = AiAssistState.Failed(reason)
    }

    private fun ApiError.toFailure(): AiFailure = when (this) {
        is ApiError.Unauthorized -> AiFailure.NOT_SIGNED_IN
        is ApiError.QuotaExceeded -> AiFailure.QUOTA
        is ApiError.Network -> if (cause is InterruptedIOException) AiFailure.TIMEOUT else AiFailure.NETWORK
        else -> AiFailure.SERVER
    }

    companion object {
        /** `@MaxLength(2000)` on the backend's rewrite and translate DTOs. */
        const val MAX_TEXT_LENGTH = 2000
    }
}
