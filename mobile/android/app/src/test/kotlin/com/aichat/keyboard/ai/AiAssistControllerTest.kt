package com.aichat.keyboard.ai

import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.Tone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiAssistControllerTest {
    private class FakeEditor(var text: String? = "xin chao", var available: Boolean = true) : AiEditorPort {
        val replaced = mutableListOf<String>()

        /** The field is longer than the read cap: only part of it is visible. */
        var truncated = false
        var replaceWorks = true
        override fun isAvailable() = available
        override fun readAllText(): FieldRead = when {
            truncated -> FieldRead.TooLong
            else -> text?.let { FieldRead.Text(it) } ?: FieldRead.Unreadable
        }
        override fun replaceAllText(text: String): Boolean {
            if (!replaceWorks || truncated) return false
            replaced += text
            this.text = text
            return true
        }
    }

    private class FakeBackend(var result: ApiResult<String> = ApiResult.Success("Xin chào!")) : AiTextBackend {
        val calls = mutableListOf<Pair<AiAction, String>>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun run(action: AiAction, text: String): ApiResult<String> {
            calls += action to text
            gate?.await()
            return result
        }

        override suspend fun reply(
            conversation: com.aichat.keyboard.reply.CapturedConversation,
            tone: Tone,
        ): ApiResult<ReplySuggestions> = ApiResult.Success(ReplySuggestions(emptyList()))
    }

    private fun TestScope.controller(editor: AiEditorPort, backend: AiTextBackend) =
        AiAssistController(backend, editor, this)

    private val friendly = AiAction.Rewrite(Tone.FRIENDLY)

    @Test
    fun `toggle opens and closes the panel`() = runTest {
        val c = controller(FakeEditor(), FakeBackend())
        assertEquals(AiAssistState.Closed, c.state.value)
        c.toggle()
        assertEquals(AiAssistState.Ready, c.state.value)
        c.toggle()
        assertEquals(AiAssistState.Closed, c.state.value)
    }

    @Test
    fun `successful rewrite replaces the field text and offers undo`() = runTest {
        val editor = FakeEditor(text = "  xin chao  ")
        val backend = FakeBackend()
        val c = controller(editor, backend)
        c.toggle()

        c.run(friendly)
        advanceUntilIdle()

        assertEquals(listOf<Pair<AiAction, String>>(friendly to "xin chao"), backend.calls)
        assertEquals(listOf("Xin chào!"), editor.replaced)
        assertEquals(AiAssistState.Done(original = "  xin chao  ", applied = "Xin chào!"), c.state.value)
    }

    @Test
    fun `undo restores the original text and returns to ready`() = runTest {
        val editor = FakeEditor(text = "xin chao")
        val c = controller(editor, FakeBackend())
        c.run(friendly)
        advanceUntilIdle()

        c.undo()

        assertEquals("xin chao", editor.text)
        assertEquals(AiAssistState.Ready, c.state.value)
    }

    @Test
    fun `empty field fails without calling the backend`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor(text = "   "), backend)
        c.run(friendly)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.EMPTY_TEXT), c.state.value)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `text over the backend limit fails without calling the backend`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor(text = "a".repeat(AiAssistController.MAX_TEXT_LENGTH + 1)), backend)
        c.run(friendly)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.TOO_LONG), c.state.value)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `unavailable editor such as a password field never sends text`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor(available = false), backend)
        c.run(friendly)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.UNAVAILABLE), c.state.value)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `backend errors map to user facing failures`() = runTest {
        val cases = listOf(
            ApiError.Unauthorized("x") to AiFailure.NOT_SIGNED_IN,
            ApiError.QuotaExceeded("x", 20, "rewrite") to AiFailure.QUOTA,
            ApiError.Network(IOException("offline")) to AiFailure.NETWORK,
            // A slow model is not a dead network: the phone is online, the AI just took too long.
            ApiError.Network(java.net.SocketTimeoutException("timeout")) to AiFailure.TIMEOUT,
            ApiError.Upstream("x", 502) to AiFailure.SERVER,
            ApiError.Validation("x") to AiFailure.SERVER,
        )
        for ((error, expected) in cases) {
            val editor = FakeEditor()
            val c = controller(editor, FakeBackend(result = ApiResult.Failure(error)))
            c.run(friendly)
            advanceUntilIdle()
            assertEquals(AiAssistState.Failed(expected), c.state.value, "for $error")
            assertTrue(editor.replaced.isEmpty())
        }
    }

    @Test
    fun `result is dropped when the user edited the field while waiting`() = runTest {
        val editor = FakeEditor(text = "xin chao")
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        val c = controller(editor, backend)
        c.run(friendly)
        advanceUntilIdle()
        assertEquals(AiAssistState.Loading(friendly), c.state.value)

        editor.text = "xin chao ban"
        backend.gate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(editor.replaced.isEmpty())
        assertEquals(AiAssistState.Failed(AiFailure.TEXT_CHANGED), c.state.value)
    }

    @Test
    fun `a second action while loading is ignored`() = runTest {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        val c = controller(FakeEditor(), backend)
        c.run(friendly)
        c.run(AiAction.Translate("en"))
        advanceUntilIdle()
        assertEquals(1, backend.calls.size)
        backend.gate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `reset cancels a pending request and closes the panel`() = runTest {
        val editor = FakeEditor()
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        val c = controller(editor, backend)
        c.run(friendly)
        advanceUntilIdle()

        c.reset()
        backend.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(AiAssistState.Closed, c.state.value)
        assertTrue(editor.replaced.isEmpty())
    }
    @Test
    fun `a field longer than the read cap fails as too long without calling the backend`() = runTest {
        val backend = FakeBackend()
        val editor = FakeEditor().apply { truncated = true }
        val c = controller(editor, backend)
        c.run(friendly)
        advanceUntilIdle()
        assertEquals(AiAssistState.Failed(AiFailure.TOO_LONG), c.state.value)
        assertTrue(backend.calls.isEmpty())
        assertTrue(editor.replaced.isEmpty())
    }

    @Test
    fun `undo does not overwrite text the user changed after the AI result`() = runTest {
        val editor = FakeEditor(text = "xin chao")
        val c = controller(editor, FakeBackend())
        c.run(friendly)
        advanceUntilIdle()

        editor.text = "Xin chào! hẹn gặp lại"
        c.undo()

        assertEquals("Xin chào! hẹn gặp lại", editor.text)
        assertEquals(AiAssistState.Failed(AiFailure.TEXT_CHANGED), c.state.value)
    }

    @Test
    fun `undo that cannot write the field reports unavailable`() = runTest {
        val editor = FakeEditor(text = "xin chao")
        val c = controller(editor, FakeBackend())
        c.run(friendly)
        advanceUntilIdle()

        editor.replaceWorks = false
        c.undo()

        assertEquals("Xin chào!", editor.text)
        assertEquals(AiAssistState.Failed(AiFailure.UNAVAILABLE), c.state.value)
    }

    @Test
    fun `undo with an unreadable field reports unavailable`() = runTest {
        val editor = FakeEditor(text = "xin chao")
        val c = controller(editor, FakeBackend())
        c.run(friendly)
        advanceUntilIdle()

        editor.text = null
        c.undo()

        assertEquals(AiAssistState.Failed(AiFailure.UNAVAILABLE), c.state.value)
    }
}
