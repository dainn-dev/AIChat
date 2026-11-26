package com.aichat.keyboard.share

import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyResponse
import com.aichat.keyboard.core.network.model.MessageSender.ME
import com.aichat.keyboard.core.network.model.MessageSender.THEM
import com.aichat.keyboard.core.network.model.ReplySuggestion
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.core.network.model.Usage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ShareReplyViewModelTest {
    private val chat = ParsedChat(
        messages = listOf(ChatMessage(THEM, "Tối nay rảnh không?"), ChatMessage(ME, "Chắc rảnh")),
        contactLabel = "Linh",
    )
    private val usage = Usage(repliesUsed = 1, repliesLimit = 20, screenshotsUsed = 0, screenshotsLimit = 5)
    private val requests = mutableListOf<KeyboardReplyRequest>()
    private var replyResult: ApiResult<KeyboardReplyResponse> = ApiResult.Success(
        KeyboardReplyResponse(listOf(ReplySuggestion(Tone.FLIRTY, "Rảnh cho bạn luôn đó")), null, usage),
    )
    private val created = mutableListOf<ShareReplyViewModel>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun viewModel(ocr: suspend () -> ParsedChat? = { chat }) = ShareReplyViewModel(
        readChat = ocr,
        sendReply = { request ->
            requests += request
            replyResult
        },
    ).also { created += it }

    @Test
    fun `reads the screenshot once and shows the parsed chat`() {
        var reads = 0
        val vm = viewModel { reads++; chat }
        vm.start()
        vm.start()
        val state = vm.state.value
        assertEquals(1, reads)
        assertEquals(ShareReplyViewModel.Reading.READY, state.reading)
        assertEquals(chat.messages, state.messages)
        assertEquals("Linh", state.contactLabel)
        assertEquals(ShareReplyViewModel.ReplyState.Idle, state.reply)
    }

    @Test
    fun `shows the reading state while OCR runs`() {
        val gate = CompletableDeferred<ParsedChat?>()
        val vm = viewModel { gate.await() }
        vm.start()
        assertEquals(ShareReplyViewModel.Reading.READING, vm.state.value.reading)
        gate.complete(chat)
        assertEquals(ShareReplyViewModel.Reading.READY, vm.state.value.reading)
    }

    @Test
    fun `an image without messages is unreadable and never calls the backend`() {
        val vm = viewModel { ParsedChat(emptyList()) }
        vm.start()
        assertEquals(ShareReplyViewModel.Reading.UNREADABLE, vm.state.value.reading)
        vm.requestReplies(Tone.FLIRTY)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an OCR failure is unreadable`() {
        val vm = viewModel { null }
        vm.start()
        assertEquals(ShareReplyViewModel.Reading.UNREADABLE, vm.state.value.reading)
    }

    @Test
    fun `requesting replies sends the parsed chat without platform and with storage off`() {
        val vm = viewModel()
        vm.start()
        vm.requestReplies(Tone.FLIRTY)
        val request = requests.single()
        assertEquals(chat.messages, request.conversation)
        assertEquals(Tone.FLIRTY, request.tone)
        assertNull(request.platform)
        assertEquals("Linh", request.contactLabel)
        assertEquals(false, request.remember)
        val reply = assertIs<ShareReplyViewModel.ReplyState.Suggestions>(vm.state.value.reply)
        assertEquals(listOf("Rảnh cho bạn luôn đó"), reply.texts)
        assertEquals(Tone.FLIRTY, vm.state.value.tone)
    }

    @Test
    fun `no contact label is sent when none was detected`() {
        val vm = viewModel { chat.copy(contactLabel = null) }
        vm.start()
        vm.requestReplies(Tone.FRIENDLY)
        assertNull(requests.single().contactLabel)
    }

    @Test
    fun `flipping a bubble swaps its sender in the preview and in the request`() {
        val vm = viewModel()
        vm.start()
        vm.flipSender(0)
        assertEquals(ME, vm.state.value.messages[0].sender)
        vm.flipSender(0)
        vm.flipSender(1)
        assertEquals(THEM, vm.state.value.messages[1].sender)
        vm.flipSender(7) // out of range: ignored
        vm.requestReplies(Tone.FUNNY)
        assertEquals(listOf(THEM, THEM), requests.single().conversation.map { it.sender })
    }

    @Test
    fun `flipping clears suggestions made for the old chat`() {
        val vm = viewModel()
        vm.start()
        vm.requestReplies(Tone.FLIRTY)
        vm.flipSender(0)
        assertEquals(ShareReplyViewModel.ReplyState.Idle, vm.state.value.reply)
    }

    @Test
    fun `backend errors map to share failures`() {
        val cases = listOf(
            ApiError.Unauthorized("x") to ShareFailure.NOT_SIGNED_IN,
            ApiError.QuotaExceeded("x", 20, "reply") to ShareFailure.QUOTA,
            ApiError.Network(IOException("x")) to ShareFailure.NETWORK,
            ApiError.Network(InterruptedIOException("timeout")) to ShareFailure.TIMEOUT,
            ApiError.Upstream("x", 502) to ShareFailure.SERVER,
            ApiError.Validation("x") to ShareFailure.SERVER,
        )
        for ((error, expected) in cases) {
            replyResult = ApiResult.Failure(error)
            val vm = viewModel()
            vm.start()
            vm.requestReplies(Tone.MATURE)
            val failed = assertIs<ShareReplyViewModel.ReplyState.Failed>(vm.state.value.reply)
            assertEquals(expected, failed.failure, "for $error")
        }
    }

    @Test
    fun `retry repeats the last tone`() {
        replyResult = ApiResult.Failure(ApiError.Unauthorized("x"))
        val vm = viewModel()
        vm.start()
        vm.requestReplies(Tone.PROFESSIONAL)
        replyResult = ApiResult.Success(KeyboardReplyResponse(listOf(ReplySuggestion(Tone.PROFESSIONAL, "Dạ")), null, usage))
        vm.retry()
        assertEquals(listOf(Tone.PROFESSIONAL, Tone.PROFESSIONAL), requests.map { it.tone })
        assertIs<ShareReplyViewModel.ReplyState.Suggestions>(vm.state.value.reply)
    }

    @Test
    fun `retry without a previous request does nothing`() {
        val vm = viewModel()
        vm.start()
        vm.retry()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `tones are offered flirty first`() {
        assertEquals(
            listOf(Tone.FLIRTY, Tone.FUNNY, Tone.FRIENDLY, Tone.PROFESSIONAL, Tone.MATURE),
            ShareReplyViewModel.TONES,
        )
    }
}
