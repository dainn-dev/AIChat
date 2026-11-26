/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyResponse
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.core.network.model.Tone
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InterruptedIOException

/** Why no suggestions were shown. Each one maps to a localized message and an action on the share screen. */
enum class ShareFailure { NOT_SIGNED_IN, QUOTA, NETWORK, TIMEOUT, SERVER }

/**
 * Screenshot -> reply suggestions. [readChat] runs the on-device OCR and layout analysis (null when the image could
 * not be read); [sendReply] is `POST /v1/keyboard/reply`. Only the parsed text is ever sent, never the image.
 */
class ShareReplyViewModel(
    private val readChat: suspend () -> ParsedChat?,
    private val sendReply: suspend (KeyboardReplyRequest) -> ApiResult<KeyboardReplyResponse>,
) : ViewModel() {
    enum class Reading { READING, UNREADABLE, READY }

    sealed interface ReplyState {
        data object Idle : ReplyState
        data class Loading(val tone: Tone) : ReplyState
        data class Suggestions(val tone: Tone, val texts: List<String>) : ReplyState
        data class Failed(val tone: Tone, val failure: ShareFailure) : ReplyState
    }

    data class State(
        val reading: Reading = Reading.READING,
        val messages: List<ChatMessage> = emptyList(),
        val contactLabel: String? = null,
        val tone: Tone? = null,
        val reply: ReplyState = ReplyState.Idle,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var readJob: Job? = null
    private var replyJob: Job? = null

    /** Reads the screenshot. Only the first call does anything, so it is safe from `onCreate` after rotation. */
    fun start() {
        if (readJob != null) return
        readJob = viewModelScope.launch {
            val chat = readChat()
            _state.update {
                if (chat == null || chat.messages.isEmpty()) {
                    it.copy(reading = Reading.UNREADABLE)
                } else {
                    it.copy(
                        reading = Reading.READY,
                        messages = chat.messages,
                        contactLabel = chat.contactLabel?.trim()?.take(MAX_CONTACT_LABEL)?.ifEmpty { null },
                    )
                }
            }
        }
    }

    /** Swaps `me`/`them` for one bubble the analyzer got wrong. Suggestions for the old reading are dropped. */
    fun flipSender(index: Int) {
        replyJob?.cancel()
        _state.update { state ->
            if (index !in state.messages.indices) return@update state
            val flipped = state.messages.mapIndexed { i, message ->
                if (i == index) message.copy(sender = message.sender.opposite()) else message
            }
            state.copy(messages = flipped, reply = ReplyState.Idle)
        }
    }

    fun requestReplies(tone: Tone) {
        val current = _state.value
        if (current.reading != Reading.READY || current.messages.isEmpty()) return
        replyJob?.cancel()
        _state.update { it.copy(tone = tone, reply = ReplyState.Loading(tone)) }
        replyJob = viewModelScope.launch {
            val request = KeyboardReplyRequest(
                conversation = current.messages,
                tone = tone,
                count = SUGGESTION_COUNT,
                // The chat app is not known from a screenshot, so nothing can be stored server-side (storage needs
                // platform + contact_label); remember = false makes that explicit.
                platform = null,
                contactLabel = current.contactLabel,
                remember = false,
            )
            val next = when (val result = sendReply(request)) {
                is ApiResult.Success -> ReplyState.Suggestions(tone, result.value.suggestions.map { it.text })
                is ApiResult.Failure -> ReplyState.Failed(tone, result.error.toFailure())
            }
            _state.update { it.copy(reply = next) }
        }
    }

    /** Repeats the last request (e.g. after signing in or reconnecting). */
    fun retry() {
        val tone = _state.value.tone ?: return
        requestReplies(tone)
    }

    private fun MessageSender.opposite(): MessageSender =
        if (this == MessageSender.ME) MessageSender.THEM else MessageSender.ME

    private fun ApiError.toFailure(): ShareFailure = when (this) {
        is ApiError.Unauthorized -> ShareFailure.NOT_SIGNED_IN
        is ApiError.QuotaExceeded -> ShareFailure.QUOTA
        is ApiError.Network -> if (cause is InterruptedIOException) ShareFailure.TIMEOUT else ShareFailure.NETWORK
        else -> ShareFailure.SERVER
    }

    companion object {
        /** Tone chips, flirty first (the feature's main use). */
        val TONES = listOf(Tone.FLIRTY, Tone.FUNNY, Tone.FRIENDLY, Tone.PROFESSIONAL, Tone.MATURE)

        const val SUGGESTION_COUNT = 3

        /** `@MaxLength(128)` on the backend's `contact_label`. */
        private const val MAX_CONTACT_LABEL = 128
    }
}
