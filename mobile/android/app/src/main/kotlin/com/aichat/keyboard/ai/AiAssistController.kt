/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.Tone
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
}

sealed interface AiAssistState {
    /** The smartbar shows its normal suggestions. */
    data object Closed : AiAssistState

    /** The action chips are shown. */
    data object Ready : AiAssistState

    data class Loading(val action: AiAction) : AiAssistState

    /** The field was replaced; [original] is kept for undo. */
    data class Done(val original: String) : AiAssistState

    data class Failed(val reason: AiFailure) : AiAssistState
}

/** The focused text field, as the AI assistant sees it. */
interface AiEditorPort {
    /** False when there is no editor or the field is sensitive (password). */
    fun isAvailable(): Boolean

    /** The whole field text, or null when it cannot be read. */
    fun readAllText(): String?

    fun replaceAllText(text: String): Boolean
}

/** The backend call behind an [AiAction]; returns the new text. */
fun interface AiTextBackend {
    suspend fun run(action: AiAction, text: String): ApiResult<String>
}

/**
 * State machine behind the smartbar AI button: open the chips, run one action at a time on the whole field,
 * replace the field with the result, and offer undo. Calls never throw; every failure becomes [AiAssistState.Failed].
 */
class AiAssistController(
    private val backend: AiTextBackend,
    private val editor: AiEditorPort,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AiAssistState>(AiAssistState.Closed)
    val state: StateFlow<AiAssistState> = _state.asStateFlow()

    private var job: Job? = null

    fun toggle() {
        if (_state.value == AiAssistState.Closed) _state.value = AiAssistState.Ready else reset()
    }

    /** Back to the chips after a result or an error. */
    fun dismissMessage() {
        if (_state.value is AiAssistState.Done || _state.value is AiAssistState.Failed) {
            _state.value = AiAssistState.Ready
        }
    }

    fun run(action: AiAction) {
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
