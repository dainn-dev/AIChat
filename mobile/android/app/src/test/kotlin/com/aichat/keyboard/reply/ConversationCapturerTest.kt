package com.aichat.keyboard.reply

import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ConversationCapturerTest {
    private val clipChat = CapturedConversation(listOf(ChatMessage(MessageSender.THEM, "copied")))

    private class Fake(
        override val id: String,
        private val result: CaptureResult,
        private val gate: (Map<String, UnavailableReason>) -> Boolean = { true },
    ) : ConversationProvider {
        var calls = 0
        override fun shouldTry(earlier: Map<String, UnavailableReason>) = gate(earlier)
        override suspend fun capture(): CaptureResult {
            calls++
            return result
        }
    }

    private fun clipboard() = Fake("clipboard", CaptureResult.Captured(clipChat)) {
        ClipboardReplyPolicy.mayFallBack(it.values)
    }

    @Test
    fun `the clipboard is used when the chat reader is off or the app is unsupported`() = runTest {
        for (reason in listOf(UnavailableReason.NOT_ENABLED, UnavailableReason.UNSUPPORTED_APP)) {
            val clip = clipboard()
            val outcome = ConversationCapturer(listOf(Fake("accessibility", CaptureResult.Unavailable(reason)), clip))
                .capture()
            assertIs<ConversationCapturer.Outcome.Found>(outcome, "for $reason")
            assertEquals(1, clip.calls)
        }
    }

    @Test
    fun `a supported messenger the parser missed never falls back to a stale clip`() = runTest {
        val clip = clipboard()
        val outcome = ConversationCapturer(
            listOf(Fake("accessibility", CaptureResult.Unavailable(UnavailableReason.NOTHING_FOUND)), clip),
        ).capture()

        assertEquals(
            ConversationCapturer.Outcome.NotFound(mapOf("accessibility" to UnavailableReason.NOTHING_FOUND)),
            outcome,
        )
        assertEquals(0, clip.calls)
    }
}
