package com.aichat.keyboard.ai

import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.reply.CapturedConversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The translate button: live translation of the field while typing, shown in the smartbar. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiTranslateFlowTest {
    private class FakeEditor(var text: String = "") : AiEditorPort {
        val replaced = mutableListOf<String>()
        var reads = 0
        var peeks = 0
        override fun isAvailable() = true
        override fun readAllText(): FieldRead {
            reads++
            return FieldRead.Text(text)
        }
        override fun peekAllText(): FieldRead {
            peeks++
            return FieldRead.Text(text)
        }
        override fun replaceAllText(text: String): Boolean {
            replaced += text
            this.text = text
            return true
        }
    }

    private class FakeBackend : AiTextBackend {
        val calls = mutableListOf<Pair<AiAction, String>>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: ApiError? = null
        override suspend fun run(action: AiAction, text: String): ApiResult<String> {
            calls += action to text
            gate?.await()
            failure?.let { return ApiResult.Failure(it) }
            val lang = (action as AiAction.Translate).targetLang
            return ApiResult.Success("[$lang] $text")
        }

        override suspend fun reply(conversation: CapturedConversation, tone: Tone): ApiResult<ReplySuggestions> =
            ApiResult.Success(ReplySuggestions(emptyList()))

        override suspend fun summarize(
            conversation: CapturedConversation,
            lang: com.aichat.keyboard.core.network.model.OutputLang?,
        ): ApiResult<ConversationSummary> = ApiResult.Success(ConversationSummary("s", 50))
    }

    private fun TestScope.controller(editor: AiEditorPort, backend: AiTextBackend, copied: String? = null) =
        AiAssistController(backend, editor, this, copiedText = { copied })

    private fun AiAssistController.translating() = state.value as AiAssistState.Translating

    @Test
    fun `the translate button opens and closes the live translation`() = runTest {
        val c = controller(FakeEditor(), FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertEquals(AiAssistState.Translating("en", TranslatePreview.Idle), c.state.value)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        assertEquals(AiAssistState.Closed, c.state.value)
    }

    @Test
    fun `opening translates the text already in the field`() = runTest {
        val c = controller(FakeEditor("xin chào"), FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertEquals(TranslatePreview.Ready(source = "xin chào", text = "[en] xin chào"), c.translating().preview)
    }

    @Test
    fun `typing translates once the user pauses, without finishing the word being composed`() = runTest {
        val editor = FakeEditor()
        val backend = FakeBackend()
        val c = controller(editor, backend)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()

        editor.text = "xin"
        c.onFieldEdited()
        advanceTimeBy(AiAssistController.TRANSLATE_DEBOUNCE_MS / 2)
        editor.text = "xin chào"
        c.onFieldEdited()
        advanceTimeBy(AiAssistController.TRANSLATE_DEBOUNCE_MS / 2)
        runCurrent()
        assertTrue(backend.calls.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf<Pair<AiAction, String>>(AiAction.Translate("en") to "xin chào"), backend.calls)
        assertEquals(0, editor.reads)
        assertTrue(editor.peeks > 0)
    }

    @Test
    fun `an unchanged field is not translated again`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor("hello"), backend)
        c.toggleTranslate(defaultLang = "vi", copiedDefaultLang = "en")
        advanceUntilIdle()
        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `an emptied field clears the preview without calling the backend`() = runTest {
        val editor = FakeEditor("hello")
        val backend = FakeBackend()
        val c = controller(editor, backend)
        c.toggleTranslate(defaultLang = "vi", copiedDefaultLang = "en")
        advanceUntilIdle()
        editor.text = "  "
        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(TranslatePreview.Idle, c.translating().preview)
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `switching the language translates again right away and is remembered`() = runTest {
        val c = controller(FakeEditor("xin chào"), FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.toggleTranslateLangPicker()
        assertTrue(c.translating().choosingLang)

        c.setTranslateLang("ja")
        advanceUntilIdle()
        assertEquals(TranslatePreview.Ready("xin chào", "[ja] xin chào"), c.translating().preview)
        assertEquals(false, c.translating().choosingLang)

        c.reset()
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        assertEquals("ja", c.translating().targetLang)
    }

    @Test
    fun `a request for the old language is cancelled when the language changes`() = runTest {
        val backend = FakeBackend().apply { gate = CompletableDeferred() }
        val c = controller(FakeEditor("xin chào"), backend)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        runCurrent()
        backend.gate = null
        c.setTranslateLang("ko")
        advanceUntilIdle()
        assertEquals(TranslatePreview.Ready("xin chào", "[ko] xin chào"), c.translating().preview)
    }

    @Test
    fun `tapping the preview replaces the field and the replaced text is not translated again`() = runTest {
        val editor = FakeEditor("xin chào")
        val backend = FakeBackend()
        val c = controller(editor, backend)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()

        c.applyTranslation()
        assertEquals(listOf("[en] xin chào"), editor.replaced)
        assertEquals(TranslatePreview.Applied(original = "xin chào", text = "[en] xin chào"), c.translating().preview)

        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `after sending, the next message is translated too`() = runTest {
        val editor = FakeEditor("xin chào")
        val c = controller(editor, FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.applyTranslation()

        editor.text = ""
        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(TranslatePreview.Idle, c.translating().preview)

        editor.text = "cảm ơn"
        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(TranslatePreview.Ready("cảm ơn", "[en] cảm ơn"), c.translating().preview)
    }

    @Test
    fun `a field edited since the preview is translated again instead of replaced`() = runTest {
        val editor = FakeEditor("xin chào")
        val c = controller(editor, FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        editor.text = "xin chào bạn"

        c.applyTranslation()
        advanceUntilIdle()
        assertTrue(editor.replaced.isEmpty())
        assertEquals(TranslatePreview.Ready("xin chào bạn", "[en] xin chào bạn"), c.translating().preview)
    }

    @Test
    fun `undo restores the original text while the field still holds the translation`() = runTest {
        val editor = FakeEditor("xin chào")
        val c = controller(editor, FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.applyTranslation()

        c.undoTranslation()
        assertEquals("xin chào", editor.text)
        assertEquals(TranslatePreview.Ready("xin chào", "[en] xin chào"), c.translating().preview)
    }

    @Test
    fun `undo after the user edited the translation fails as text changed`() = runTest {
        val editor = FakeEditor("xin chào")
        val c = controller(editor, FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.applyTranslation()
        editor.text = "something else"

        c.undoTranslation()
        assertEquals("something else", editor.text)
        assertEquals(TranslatePreview.Failed(AiFailure.TEXT_CHANGED), c.translating().preview)
    }

    @Test
    fun `a backend failure is shown in the preview`() = runTest {
        val backend = FakeBackend().apply { failure = ApiError.QuotaExceeded("x", 20, "translate") }
        val c = controller(FakeEditor("xin chào"), backend)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertEquals(TranslatePreview.Failed(AiFailure.QUOTA), c.translating().preview)
    }

    @Test
    fun `a too long field fails without calling the backend`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor("a".repeat(AiAssistController.MAX_TEXT_LENGTH + 1)), backend)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertEquals(TranslatePreview.Failed(AiFailure.TOO_LONG), c.translating().preview)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `the AI button switches from live translation to the AI chips`() = runTest {
        val c = controller(FakeEditor(), FakeBackend())
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        c.toggle()
        assertEquals(AiAssistState.Ready, c.state.value)
    }

    @Test
    fun `the translate button switches from the AI chips to live translation`() = runTest {
        val c = controller(FakeEditor(), FakeBackend())
        c.toggle()
        c.toggleTranslate(defaultLang = "vi", copiedDefaultLang = "en")
        assertEquals("vi", c.translating().targetLang)
    }

    @Test
    fun `field edits outside live translation do nothing`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor("hello"), backend)
        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(AiAssistState.Closed, c.state.value)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `a copied message opens the language chips, and picking one translates it`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor(), backend, copied = "How are you?")
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertTrue(c.translating().choosingLang)
        assertEquals(CopiedTranslation("How are you?", targetLang = "vi"), c.translating().copied)
        assertTrue(backend.calls.isEmpty())

        c.setTranslateLang("vi")
        advanceUntilIdle()
        assertEquals(listOf<Pair<AiAction, String>>(AiAction.Translate("vi") to "How are you?"), backend.calls)
        assertEquals(
            TranslatePreview.Ready("How are you?", "[vi] How are you?"),
            c.translating().copied!!.preview,
        )
        assertEquals(false, c.translating().choosingLang)
    }

    @Test
    fun `the copied message language does not change the language replies are typed in`() = runTest {
        val c = controller(FakeEditor(), FakeBackend(), copied = "How are you?")
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.setTranslateLang("vi")
        advanceUntilIdle()
        assertEquals("en", c.translating().targetLang)
    }

    @Test
    fun `the copied message language is remembered`() = runTest {
        val c = controller(FakeEditor(), FakeBackend(), copied = "Hola")
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.setTranslateLang("ja")
        advanceUntilIdle()
        c.reset()
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertEquals("ja", c.translating().copied!!.targetLang)
    }

    @Test
    fun `typing while the copied translation is shown does not translate the field`() = runTest {
        val editor = FakeEditor()
        val backend = FakeBackend()
        val c = controller(editor, backend, copied = "How are you?")
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.setTranslateLang("vi")
        advanceUntilIdle()

        editor.text = "khỏe"
        c.onFieldEdited()
        advanceUntilIdle()
        assertEquals(1, backend.calls.size)
    }

    @Test
    fun `closing the copied translation goes back to live translation of the field`() = runTest {
        val editor = FakeEditor()
        val c = controller(editor, FakeBackend(), copied = "How are you?")
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.setTranslateLang("vi")
        advanceUntilIdle()
        editor.text = "khỏe"

        c.dismissCopied()
        advanceUntilIdle()
        assertEquals(null, c.translating().copied)
        assertEquals(TranslatePreview.Ready("khỏe", "[en] khỏe"), c.translating().preview)
    }

    @Test
    fun `a copied message over the backend limit fails without calling it`() = runTest {
        val backend = FakeBackend()
        val c = controller(FakeEditor(), backend, copied = "a".repeat(AiAssistController.MAX_TEXT_LENGTH + 1))
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        c.setTranslateLang("vi")
        advanceUntilIdle()
        assertEquals(TranslatePreview.Failed(AiFailure.TOO_LONG), c.translating().copied!!.preview)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `without a copied message the translate button translates the field as before`() = runTest {
        val c = controller(FakeEditor("xin chào"), FakeBackend(), copied = null)
        c.toggleTranslate(defaultLang = "en", copiedDefaultLang = "vi")
        advanceUntilIdle()
        assertEquals(null, c.translating().copied)
        assertEquals(false, c.translating().choosingLang)
        assertEquals(TranslatePreview.Ready("xin chào", "[en] xin chào"), c.translating().preview)
    }
}
