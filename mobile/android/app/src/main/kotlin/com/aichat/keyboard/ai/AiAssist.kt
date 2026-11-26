/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.aichat.keyboard.core.network.AiChat
import com.aichat.keyboard.core.network.AiChatClient
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.error.errorOrNull
import com.aichat.keyboard.core.network.error.map
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.KeyboardRewriteRequest
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.reply.AccessibilityConversationProvider
import com.aichat.keyboard.reply.CapturedConversation
import com.aichat.keyboard.reply.ClipboardConversationProvider
import com.aichat.keyboard.reply.ConversationCapturer
import com.aichat.keyboard.core.network.model.KeyboardTranslateRequest
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.editor.EditorInstance
import dev.patrickgold.florisboard.ime.keyboard.KeyboardManager
import dev.patrickgold.florisboard.ime.text.key.KeyVariation
import dev.patrickgold.florisboard.keyboardManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Per-process entry point of the smartbar AI button. The controller lives as long as the app process, like
 * [AiChat]; [onFieldChanged] drops any running request when the user moves to another field.
 */
object AiAssist {
    @Volatile
    private var controller: AiAssistController? = null

    fun controller(context: Context): AiAssistController =
        controller ?: synchronized(this) {
            controller ?: create(context.applicationContext).also { controller = it }
        }

    /** New field or keyboard closed: a result must never land in a different field. */
    fun onFieldChanged() {
        controller?.reset()
    }

    private fun create(appContext: Context): AiAssistController {
        val editorInstance by appContext.editorInstance()
        val keyboardManager by appContext.keyboardManager()
        return AiAssistController(
            backend = AiChatTextBackend(
                client = AiChat.client(appContext),
                // Incognito keyboard (private fields / incognito mode): the backend must not store the chat.
                isIncognito = { keyboardManager.activeState.isIncognitoMode },
            ),
            editor = ImeAiEditorPort(editorInstance, keyboardManager),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            // Order matters: the first provider that finds the chat wins.
            capturer = ConversationCapturer(
                listOf(
                    AccessibilityConversationProvider(appContext),
                    ClipboardConversationProvider(appContext),
                ),
            ),
        )
    }
}

/** Rewrite and translate through the backend's keyboard endpoints (`/v1/keyboard/rewrite|translate`). */
internal class AiChatTextBackend(
    private val client: AiChatClient,
    private val isIncognito: () -> Boolean,
) : AiTextBackend {
    override suspend fun reply(conversation: CapturedConversation, tone: Tone): ApiResult<List<String>> {
        val request = KeyboardReplyRequest(
            conversation = conversation.messages,
            tone = tone,
            count = REPLY_COUNT,
            platform = conversation.platform,
            contactLabel = conversation.contactLabel,
            remember = if (isIncognito()) false else null,
        )
        val result = client.reply(request).map { response -> response.suggestions.map { it.text } }
        result.errorOrNull()?.let { Log.w(TAG, "Reply failed: $it") }
        return result
    }

    override suspend fun run(action: AiAction, text: String): ApiResult<String> {
        val result = when (action) {
            // `lang` omitted: the backend answers in the language of the text.
            is AiAction.Rewrite -> client.rewrite(KeyboardRewriteRequest(text = text, tone = action.tone)).map { it.text }
            is AiAction.Translate ->
                client.translate(KeyboardTranslateRequest(text = text, targetLang = action.targetLang)).map { it.text }
            // Replies go through reply(); the controller never routes them here.
            is AiAction.Reply -> error("Reply is not a text transform")
        }
        // Error type and the server's message only; never the user's text.
        result.errorOrNull()?.let { Log.w(TAG, "${action::class.simpleName} failed: $it") }
        return result
    }

    private companion object {
        const val TAG = "AiAssist"
        const val REPLY_COUNT = 3
    }
}

/**
 * The whole focused field through the [InputConnection]: the text before the cursor, the selection and the text
 * after it. Reads are capped just above the backend limit, so a longer field is reported as too long instead of
 * being cut silently.
 */
internal class ImeAiEditorPort(
    private val editorInstance: EditorInstance,
    private val keyboardManager: KeyboardManager,
) : AiEditorPort {
    override fun isAvailable(): Boolean =
        FlorisImeService.currentInputConnection() != null &&
            !editorInstance.activeInfo.isRawInputEditor &&
            keyboardManager.activeState.keyVariation != KeyVariation.PASSWORD

    override fun readAllText(): String? {
        val ic = FlorisImeService.currentInputConnection() ?: return null
        editorInstance.viInput.finishWord()
        ic.finishComposingText()
        return ic.snapshot()?.text
    }

    override fun replaceAllText(text: String): Boolean {
        val ic = FlorisImeService.currentInputConnection() ?: return false
        editorInstance.viInput.reset()
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            val snapshot = ic.snapshot() ?: return false
            if (snapshot.selected.isNotEmpty()) ic.commitText("", 1)
            ic.deleteSurroundingText(snapshot.before.length, snapshot.after.length)
            return ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
    }

    private class Snapshot(val before: String, val selected: String, val after: String) {
        val text: String get() = before + selected + after
    }

    private fun InputConnection.snapshot(): Snapshot? {
        val before = getTextBeforeCursor(READ_LIMIT, 0)
        val after = getTextAfterCursor(READ_LIMIT, 0)
        if (before == null && after == null) return null
        return Snapshot(
            before = before?.toString().orEmpty(),
            selected = getSelectedText(0)?.toString().orEmpty(),
            after = after?.toString().orEmpty(),
        )
    }

    private companion object {
        const val READ_LIMIT = AiAssistController.MAX_TEXT_LENGTH + 1
    }
}
