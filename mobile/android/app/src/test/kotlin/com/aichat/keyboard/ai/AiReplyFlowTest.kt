package com.aichat.keyboard.ai

import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.reply.CaptureResult
import com.aichat.keyboard.reply.CapturedConversation
import com.aichat.keyboard.reply.ConversationCapturer
import com.aichat.keyboard.reply.ConversationProvider
import com.aichat.keyboard.reply.UnavailableReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiReplyFlowTest {
    private val chat = CapturedConversation(
        messages = listOf(ChatMessage(MessageSender.THEM, "Tối nay rảnh không?")),
        platform = "messenger",
        contactLabel = "Lan",
    )

    private class FakeProvider(override val id: String, private val result: CaptureResult) : ConversationProvider {
        var calls = 0
        override suspend fun capture(): CaptureResult {
            calls++
            return result
        }
    }

    private class FakeEditor(var text: String = "") : AiEditorPort {
        var available = true
        var truncated = false
        var replaceCalls = 0
        override fun isAvailable() = available
        override fun readAllText(): FieldRead = if (truncated) FieldRead.TooLong else FieldRead.Text(text)
        override fun replaceAllText(text: String): Boolean {
            replaceCalls++
            if (truncated) return false
            this.text = text
            return true
        }
    }

    private class FakeBackend(
        var replies: ApiResult<List<String>> = ApiResult.Success(listOf("Rảnh chứ, đi đâu nè?", "Có, còn bạn?")),
    ) : AiTextBackend {
        val replyCalls = mutableListOf<Pair<CapturedConversation, Tone>>()
        override suspend fun run(action: AiAction, text: String): ApiResult<String> = ApiResult.Success(text)
        override suspend fun reply(conversation: CapturedConversation, tone: Tone): ApiResult<List<String>> {
            replyCalls += conversation to tone
            return replies
        }
    }

    private fun TestScope.controller(
        providers: List<ConversationProvider>,
        backend: AiTextBackend = FakeBackend(),
        editor: AiEditorPort = FakeEditor(),
    ) = AiAssistController(backend, editor, this, ConversationCapturer(providers))

    @Test
    fun `reply asks for a tone first`() = runTest {
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))))
        c.toggle()
        c.startReply()
        assertEquals(AiAssistState.ChoosingReplyTone, c.state.value)
    }

    @Test
    fun `choosing a tone captures the chat and shows the suggestions`() = runTest {
        val backend = FakeBackend()
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))), backend)
        c.startReply()

        c.requestReplies(Tone.FLIRTY)
        advanceUntilIdle()

        assertEquals(listOf(chat to Tone.FLIRTY), backend.replyCalls)
        assertEquals(AiAssistState.Suggestions(listOf("Rảnh chứ, đi đâu nè?", "Có, còn bạn?")), c.state.value)
    }

    @Test
    fun `providers are tried in order until one finds the chat`() = runTest {
        val off = FakeProvider("accessibility", CaptureResult.Unavailable(UnavailableReason.NOT_ENABLED))
        val clip = FakeProvider("clipboard", CaptureResult.Captured(chat))
        val c = controller(listOf(off, clip))
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(1, off.calls)
        assertEquals(1, clip.calls)
        assertTrue(c.state.value is AiAssistState.Suggestions)
    }

    @Test
    fun `no readable chat fails and says whether reading is switched off`() = runTest {
        val backend = FakeBackend()
        val c = controller(
            listOf(FakeProvider("accessibility", CaptureResult.Unavailable(UnavailableReason.NOT_ENABLED))),
            backend,
        )
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.NO_CONVERSATION, captureDisabled = true), c.state.value)
        assertTrue(backend.replyCalls.isEmpty())
    }

    @Test
    fun `a throwing provider counts as nothing found`() = runTest {
        val boom = object : ConversationProvider {
            override val id = "boom"
            override suspend fun capture(): CaptureResult = error("broken")
        }
        val c = controller(listOf(boom))
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.NO_CONVERSATION, captureDisabled = false), c.state.value)
    }

    @Test
    fun `picking a suggestion fills the field and offers undo`() = runTest {
        val editor = FakeEditor(text = "dang go")
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))), editor = editor)
        c.requestReplies(Tone.FLIRTY)
        advanceUntilIdle()

        c.pickSuggestion("Rảnh chứ, đi đâu nè?")

        assertEquals("Rảnh chứ, đi đâu nè?", editor.text)
        assertEquals(AiAssistState.Done(original = "dang go", applied = "Rảnh chứ, đi đâu nè?"), c.state.value)
        c.undo()
        assertEquals("dang go", editor.text)
    }

    @Test
    fun `reply backend errors map like the other actions`() = runTest {
        val backend = FakeBackend(replies = ApiResult.Failure(ApiError.QuotaExceeded("x", 20, "reply")))
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))), backend)
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.QUOTA), c.state.value)
    }

    @Test
    fun `an empty suggestion list is a server failure, not an empty row`() = runTest {
        val c = controller(
            listOf(FakeProvider("a", CaptureResult.Captured(chat))),
            FakeBackend(replies = ApiResult.Success(emptyList())),
        )
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.SERVER), c.state.value)
    }

    @Test
    fun `password field never sends the chat`() = runTest {
        val backend = FakeBackend()
        val editor = FakeEditor().apply { available = false }
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))), backend, editor)
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.UNAVAILABLE), c.state.value)
        assertTrue(backend.replyCalls.isEmpty())
    }
    @Test
    fun `picking a suggestion in a field longer than the read cap fails as too long without editing`() = runTest {
        val editor = FakeEditor(text = "x".repeat(10)).apply { truncated = true }
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))), editor = editor)
        c.requestReplies(Tone.FLIRTY)
        advanceUntilIdle()

        c.pickSuggestion("Rảnh chứ, đi đâu nè?")

        assertEquals(AiAssistState.Failed(AiFailure.TOO_LONG), c.state.value)
        assertEquals(0, editor.replaceCalls)
    }

    @Test
    fun `asking again in another tone from the suggestions keeps the tall bar while loading`() = runTest {
        // First request answers at once; the second waits so its Loading state can be observed.
        val backend = object : AiTextBackend {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            override suspend fun run(action: AiAction, text: String): ApiResult<String> = ApiResult.Success(text)
            override suspend fun reply(conversation: CapturedConversation, tone: Tone): ApiResult<List<String>> {
                if (calls++ > 0) gate.await()
                return ApiResult.Success(listOf("ok"))
            }
        }
        val c = controller(listOf(FakeProvider("a", CaptureResult.Captured(chat))), backend)
        c.requestReplies(Tone.FLIRTY)
        assertEquals(AiAssistState.Loading(AiAction.Reply(Tone.FLIRTY), fromSuggestions = false), c.state.value)
        advanceUntilIdle()
        assertTrue(c.state.value is AiAssistState.Suggestions)

        c.requestReplies(Tone.FUNNY)
        advanceUntilIdle()

        val loading = c.state.value
        assertEquals(AiAssistState.Loading(AiAction.Reply(Tone.FUNNY), fromSuggestions = true), loading)
        assertEquals(AiSmartbarLayout.SUGGESTION_ROWS, AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = loading))
        backend.gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a chat reader that is enabled but not connected gets its own failure`() = runTest {
        val c = controller(
            listOf(
                FakeProvider("accessibility", CaptureResult.Unavailable(UnavailableReason.NOT_CONNECTED)),
                FakeProvider("clipboard", CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND)),
            ),
        )
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.CAPTURE_NOT_CONNECTED), c.state.value)
    }

    @Test
    fun `an unsupported app with no usable clip says which apps can be read`() = runTest {
        val c = controller(
            listOf(
                FakeProvider("accessibility", CaptureResult.Unavailable(UnavailableReason.UNSUPPORTED_APP)),
                FakeProvider("clipboard", CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND)),
            ),
        )
        c.requestReplies(Tone.FRIENDLY)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.UNSUPPORTED_CHAT_APP), c.state.value)
    }
}
