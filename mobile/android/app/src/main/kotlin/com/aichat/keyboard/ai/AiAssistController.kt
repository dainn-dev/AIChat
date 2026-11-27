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
import com.aichat.keyboard.sticker.StickerTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** The chat reader is enabled in settings but not connected; switching it off and on again fixes it. */
    CAPTURE_NOT_CONNECTED,

    /** The app in front is not one the chat reader supports, and there was no usable copied message. */
    UNSUPPORTED_CHAT_APP,
}

sealed interface AiAssistState {
    /** The smartbar shows its normal suggestions. */
    data object Closed : AiAssistState

    /** The action chips are shown. */
    data object Ready : AiAssistState

    /** [fromSuggestions]: a reply request started from [Suggestions] (another tone), so the tall bar stays. */
    data class Loading(val action: AiAction, val fromSuggestions: Boolean = false) : AiAssistState

    /**
     * The field was replaced with [applied]; [original] is kept for undo, which only restores it while the field
     * still holds [applied].
     */
    data class Done(val original: String, val applied: String) : AiAssistState

    /** Reply flow: the tone chips (Flirty, Funny, ...) are shown. */
    data object ChoosingReplyTone : AiAssistState

    /**
     * Reply flow: suggested replies; picking one fills the field. [stickerTags]: stickers that fit the chat
     * (from the backend's `sticker_tags`), shown next to the replies.
     */
    data class Suggestions(val replies: List<String>, val stickerTags: List<StickerTag> = emptyList()) : AiAssistState

    /**
     * [captureDisabled] is set with [AiFailure.NO_CONVERSATION] when a chat reader is switched off (e.g. the
     * accessibility service), so the UI can offer to turn it on.
     */
    data class Failed(val reason: AiFailure, val captureDisabled: Boolean = false) : AiAssistState

    /**
     * Live translation (the translate button): the field is translated into [targetLang] each time the user pauses
     * typing, and the result is shown in the smartbar; tapping it replaces the field. [choosingLang]: the language
     * chips are shown instead of the preview.
     */
    data class Translating(
        val targetLang: String,
        val preview: TranslatePreview = TranslatePreview.Idle,
        val choosingLang: Boolean = false,
        /** A message the user just copied (e.g. the chat partner's), translated instead of the field until closed. */
        val copied: CopiedTranslation? = null,
    ) : AiAssistState
}

/**
 * The copied message [source] and its translation into [targetLang]. Its language is separate from the field's:
 * the partner's message is read in the user's language, the reply is sent in theirs. [preview] stays
 * [TranslatePreview.Idle] until a language is picked.
 */
data class CopiedTranslation(
    val source: String,
    val targetLang: String,
    val preview: TranslatePreview = TranslatePreview.Idle,
)

/** What the live translation shows next to its language chip. */
sealed interface TranslatePreview {
    /** The field is empty. */
    data object Idle : TranslatePreview

    /** [source] (the trimmed field) is being translated. */
    data class Loading(val source: String) : TranslatePreview

    /** [text] is the translation of [source]. */
    data class Ready(val source: String, val text: String) : TranslatePreview

    /** The field was replaced with [text]; [original] is kept for undo. */
    data class Applied(val original: String, val text: String) : TranslatePreview

    data class Failed(val reason: AiFailure) : TranslatePreview
}

/** One read of the whole focused field. */
sealed interface FieldRead {
    data class Text(val text: String) : FieldRead

    /** No editor, or the editor returned nothing. */
    data object Unreadable : FieldRead

    /**
     * The field is longer than the read cap, so only part of it was seen. Replacing it would delete only part,
     * and undo could not restore it, so nothing is edited.
     */
    data object TooLong : FieldRead
}

/** The focused text field, as the AI assistant sees it. */
interface AiEditorPort {
    /** False when there is no editor or the field is sensitive (password). */
    fun isAvailable(): Boolean

    /** The whole field text, [FieldRead.TooLong] past the read cap, or [FieldRead.Unreadable]. */
    fun readAllText(): FieldRead

    /**
     * Like [readAllText], but leaves the word being composed alone (live translation reads while the user types,
     * and finishing the word would break Vietnamese tone typing).
     */
    fun peekAllText(): FieldRead = readAllText()

    /** Replaces the whole field; false (and no edit) when it cannot, including a field past the read cap. */
    fun replaceAllText(text: String): Boolean
}

/** Suggested replies, best first, and the sticker tags that fit the chat (may be empty). */
data class ReplySuggestions(val replies: List<String>, val stickerTags: List<StickerTag> = emptyList())

/** The backend calls behind the AI button. */
interface AiTextBackend {
    /** Rewrite or translate [text]; returns the new text. */
    suspend fun run(action: AiAction, text: String): ApiResult<String>

    /** Suggested replies to [conversation] in [tone], best first. */
    suspend fun reply(conversation: CapturedConversation, tone: Tone): ApiResult<ReplySuggestions>
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
    /** The message the user just copied, if any is recent and safe to send (see ClipboardReplyPolicy). */
    private val copiedText: suspend () -> String? = { null },
) {
    private val _state = MutableStateFlow<AiAssistState>(AiAssistState.Closed)
    val state: StateFlow<AiAssistState> = _state.asStateFlow()

    private var job: Job? = null

    /** The running or debounced live translation, separate from [job] so typing never cancels another action. */
    private var translateJob: Job? = null

    /** The last language picked in live translation, for the next time it opens (this process only). */
    private var lastTranslateLang: String? = null

    /** The last language a copied message was translated into (this process only). */
    private var lastCopiedLang: String? = null

    private val translating: AiAssistState.Translating? get() = _state.value as? AiAssistState.Translating

    /** The AI button: opens the chips (also from live translation, which it replaces) or closes them. */
    fun toggle() {
        val s = _state.value
        if (s == AiAssistState.Closed || s is AiAssistState.Translating) {
            cancelTranslation()
            _state.value = AiAssistState.Ready
        } else {
            reset()
        }
    }

    /**
     * The translate button: with a message just copied, opens the language chips to translate it (into the last
     * language used for one, else [copiedDefaultLang]); otherwise opens live translation of the field into the last
     * language used, else [defaultLang], and translates what is already there. Closes it when open. Ignored while
     * another action runs.
     */
    fun toggleTranslate(defaultLang: String, copiedDefaultLang: String) {
        when (_state.value) {
            is AiAssistState.Translating -> reset()
            is AiAssistState.Loading -> Unit
            else -> {
                _state.value = AiAssistState.Translating(lastTranslateLang ?: defaultLang)
                cancelTranslation()
                translateJob = scope.launch {
                    val copied = copiedText()?.trim()?.takeIf { it.isNotEmpty() }
                    val s = translating ?: return@launch
                    if (copied == null) return@launch translateField(force = false)
                    _state.value = s.copy(
                        choosingLang = true,
                        copied = CopiedTranslation(copied, lastCopiedLang ?: copiedDefaultLang),
                    )
                }
            }
        }
    }

    /** Shows or hides the language chips of live translation. */
    fun toggleTranslateLangPicker() {
        val s = translating ?: return
        _state.value = s.copy(choosingLang = !s.choosingLang)
    }

    /**
     * Picks the language and translates right away: the copied message while it is shown, else the field (into
     * which replies are typed).
     */
    fun setTranslateLang(lang: String) {
        val s = translating ?: return
        if (s.copied != null) return translateCopied(s, s.copied, lang)
        lastTranslateLang = lang
        val preview = if (s.preview is TranslatePreview.Applied) s.preview else TranslatePreview.Idle
        _state.value = s.copy(targetLang = lang, choosingLang = false, preview = preview)
        scheduleTranslation(delayMs = 0, force = s.preview !is TranslatePreview.Applied)
    }

    /** The field changed: in live translation, translate it once the user pauses typing. */
    fun onFieldEdited() {
        val s = translating ?: return
        // The copied message's translation stays while the user types a reply; the field waits until it closes.
        if (s.copied == null) scheduleTranslation(delayMs = TRANSLATE_DEBOUNCE_MS)
    }

    /** Closes the copied message's translation and goes back to live translation of the field. */
    fun dismissCopied() {
        val s = translating ?: return
        if (s.copied == null) return
        _state.value = s.copy(copied = null, choosingLang = false)
        scheduleTranslation(delayMs = 0)
    }

    private fun translateCopied(s: AiAssistState.Translating, copied: CopiedTranslation, lang: String) {
        lastCopiedLang = lang
        cancelTranslation()
        if (copied.source.length > MAX_TEXT_LENGTH) {
            return setCopied(copied.copy(targetLang = lang, preview = TranslatePreview.Failed(AiFailure.TOO_LONG)))
        }
        _state.value = s.copy(
            choosingLang = false,
            copied = copied.copy(targetLang = lang, preview = TranslatePreview.Loading(copied.source)),
        )
        translateJob = scope.launch {
            val result = backend.run(AiAction.Translate(lang), copied.source)
            if (translating?.copied?.targetLang != lang) return@launch
            setCopied(
                copied.copy(
                    targetLang = lang,
                    preview = when (result) {
                        is ApiResult.Failure -> TranslatePreview.Failed(result.error.toFailure())
                        is ApiResult.Success -> TranslatePreview.Ready(copied.source, result.value)
                    },
                ),
            )
        }
    }

    /** Updates the copied translation only while it is still shown. */
    private fun setCopied(copied: CopiedTranslation) {
        val s = translating ?: return
        if (s.copied == null) return
        _state.value = s.copy(copied = copied, choosingLang = false)
    }

    /** Replaces the field with the shown translation, or translates again when the field changed since. */
    fun applyTranslation() {
        val ready = translating?.preview as? TranslatePreview.Ready ?: return
        cancelTranslation()
        val original = when (val read = readField()) {
            is FieldRead.Text -> read.text
            FieldRead.TooLong -> return setPreview(TranslatePreview.Failed(AiFailure.TOO_LONG))
            FieldRead.Unreadable -> return setPreview(TranslatePreview.Failed(AiFailure.UNAVAILABLE))
        }
        if (original.trim() != ready.source) return scheduleTranslation(delayMs = 0, force = true)
        setPreview(
            if (editor.replaceAllText(ready.text)) {
                TranslatePreview.Applied(original, ready.text)
            } else {
                TranslatePreview.Failed(AiFailure.UNAVAILABLE)
            },
        )
    }

    /** Restores the text from before [applyTranslation], but only while the field still holds the translation. */
    fun undoTranslation() {
        val applied = translating?.preview as? TranslatePreview.Applied ?: return
        cancelTranslation()
        setPreview(
            when (val read = readField()) {
                FieldRead.Unreadable -> TranslatePreview.Failed(AiFailure.UNAVAILABLE)
                FieldRead.TooLong -> TranslatePreview.Failed(AiFailure.TEXT_CHANGED)
                is FieldRead.Text -> when {
                    read.text != applied.text -> TranslatePreview.Failed(AiFailure.TEXT_CHANGED)
                    editor.replaceAllText(applied.original) ->
                        TranslatePreview.Ready(applied.original.trim(), applied.text)
                    else -> TranslatePreview.Failed(AiFailure.UNAVAILABLE)
                }
            },
        )
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
        val original = when (val read = readField()) {
            is FieldRead.Text -> read.text
            FieldRead.TooLong -> return fail(AiFailure.TOO_LONG)
            FieldRead.Unreadable -> return fail(AiFailure.UNAVAILABLE)
        }
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
        _state.value = AiAssistState.Loading(action, fromSuggestions = _state.value is AiAssistState.Suggestions)
        job = scope.launch {
            _state.value = when (val outcome = capturer.capture()) {
                is ConversationCapturer.Outcome.NotFound -> noConversation(outcome.reasons.values)
                is ConversationCapturer.Outcome.Found -> when (val result = backend.reply(outcome.conversation, tone)) {
                    is ApiResult.Failure -> AiAssistState.Failed(result.error.toFailure())
                    is ApiResult.Success -> result.value.replies.filter { it.isNotBlank() }.let { replies ->
                        if (replies.isEmpty()) {
                            AiAssistState.Failed(AiFailure.SERVER)
                        } else {
                            AiAssistState.Suggestions(replies, result.value.stickerTags)
                        }
                    }
                }
            }
        }
    }

    /** Fills the field with [reply] (replacing any draft, which undo restores). */
    fun pickSuggestion(reply: String) {
        if (_state.value !is AiAssistState.Suggestions) return
        val original = when (val read = readField()) {
            is FieldRead.Text -> read.text
            FieldRead.TooLong -> return fail(AiFailure.TOO_LONG)
            FieldRead.Unreadable -> return fail(AiFailure.UNAVAILABLE)
        }
        _state.value = if (editor.replaceAllText(reply)) {
            AiAssistState.Done(original, applied = reply)
        } else {
            AiAssistState.Failed(AiFailure.UNAVAILABLE)
        }
    }

    /** Restores the text from before the AI edit, but only while the field still holds the AI text. */
    fun undo() {
        val done = _state.value as? AiAssistState.Done ?: return
        _state.value = when (val read = readField()) {
            FieldRead.Unreadable -> AiAssistState.Failed(AiFailure.UNAVAILABLE)
            FieldRead.TooLong -> AiAssistState.Failed(AiFailure.TEXT_CHANGED)
            is FieldRead.Text -> when {
                read.text != done.applied -> AiAssistState.Failed(AiFailure.TEXT_CHANGED)
                editor.replaceAllText(done.original) -> AiAssistState.Ready
                else -> AiAssistState.Failed(AiFailure.UNAVAILABLE)
            }
        }
    }

    /** Cancels any request and closes the chips (new field, keyboard hidden). */
    fun reset() {
        job?.cancel()
        job = null
        cancelTranslation()
        _state.value = AiAssistState.Closed
    }

    private fun cancelTranslation() {
        translateJob?.cancel()
        translateJob = null
    }

    /** Replaces the previous translation request: typing on cancels it, so only the latest text is translated. */
    private fun scheduleTranslation(delayMs: Long, force: Boolean = false) {
        cancelTranslation()
        translateJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            translateField(force)
        }
    }

    /** [force]: translate even when the field is what the preview already shows (the language changed). */
    private suspend fun translateField(force: Boolean) {
        val s = translating ?: return
        if (!editor.isAvailable()) return setPreview(TranslatePreview.Failed(AiFailure.UNAVAILABLE))
        val text = when (val read = editor.peekAllText()) {
            is FieldRead.Text -> read.text
            FieldRead.TooLong -> return setPreview(TranslatePreview.Failed(AiFailure.TOO_LONG))
            FieldRead.Unreadable -> return setPreview(TranslatePreview.Failed(AiFailure.UNAVAILABLE))
        }
        val input = text.trim()
        if (input.isEmpty()) return setPreview(TranslatePreview.Idle)
        if (input.length > MAX_TEXT_LENGTH) return setPreview(TranslatePreview.Failed(AiFailure.TOO_LONG))
        val preview = s.preview
        if (preview is TranslatePreview.Applied && text == preview.text) return
        if (!force && preview is TranslatePreview.Ready && preview.source == input) return

        val lang = s.targetLang
        setPreview(TranslatePreview.Loading(input))
        val result = backend.run(AiAction.Translate(lang), input)
        if (translating?.targetLang != lang) return
        setPreview(
            when (result) {
                is ApiResult.Failure -> TranslatePreview.Failed(result.error.toFailure())
                is ApiResult.Success -> TranslatePreview.Ready(input, result.value)
            },
        )
    }

    /** Updates the preview only while live translation is still open. */
    private fun setPreview(preview: TranslatePreview) {
        val s = translating ?: return
        _state.value = s.copy(preview = preview)
    }

    private fun apply(original: String, newText: String): AiAssistState {
        if (editor.readAllText() != FieldRead.Text(original)) return AiAssistState.Failed(AiFailure.TEXT_CHANGED)
        return if (editor.replaceAllText(newText)) {
            AiAssistState.Done(original, applied = newText)
        } else {
            AiAssistState.Failed(AiFailure.UNAVAILABLE)
        }
    }

    private fun readField(): FieldRead = if (editor.isAvailable()) editor.readAllText() else FieldRead.Unreadable

    /**
     * Why no chat was found, most actionable first: a chat reader to reconnect, one to turn on, an app it cannot
     * read, else a chat it could not read.
     */
    private fun noConversation(reasons: Collection<UnavailableReason>): AiAssistState.Failed = when {
        UnavailableReason.NOT_CONNECTED in reasons -> AiAssistState.Failed(AiFailure.CAPTURE_NOT_CONNECTED)
        UnavailableReason.NOT_ENABLED in reasons -> AiAssistState.Failed(AiFailure.NO_CONVERSATION, captureDisabled = true)
        UnavailableReason.UNSUPPORTED_APP in reasons -> AiAssistState.Failed(AiFailure.UNSUPPORTED_CHAT_APP)
        else -> AiAssistState.Failed(AiFailure.NO_CONVERSATION)
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

        /** Pause in typing before live translation calls the backend (each call counts against the quota). */
        const val TRANSLATE_DEBOUNCE_MS = 700L
    }
}
