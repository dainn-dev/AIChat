package com.aichat.keyboard.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class AiSmartbarLayoutTest {
    @Test
    fun `the smartbar grows only while reply suggestions are shown in the suggestions-only layout`() {
        assertEquals(
            AiSmartbarLayout.SUGGESTION_ROWS,
            AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Suggestions(listOf("a"))),
        )
        assertEquals(1, AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Closed))
        assertEquals(1, AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Ready))
        assertEquals(1, AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.ChoosingReplyTone))
        assertEquals(
            AiSmartbarLayout.SUGGESTION_ROWS,
            AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Summary(ConversationSummary("a", 50))),
        )
        assertEquals(1, AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Loading(AiAction.Summarize)))
        // Other layouts never show the AI panel, so they keep one row.
        assertEquals(1, AiSmartbarLayout.mainRowCount(suggestionsOnly = false, state = AiAssistState.Suggestions(listOf("a"))))
    }

    @Test
    fun `live translation is one row with nothing to translate or while picking a language`() {
        assertEquals(
            1,
            AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Translating("en")),
        )
        assertEquals(
            1,
            AiSmartbarLayout.mainRowCount(
                suggestionsOnly = true,
                state = AiAssistState.Translating("en", TranslatePreview.Ready("a", "b"), choosingLang = true),
            ),
        )
    }

    @Test
    fun `live translation keeps two rows once translating, so the bar does not jump while typing`() {
        listOf(
            TranslatePreview.Loading("a"),
            TranslatePreview.Ready("a", "b"),
            TranslatePreview.Failed(AiFailure.NETWORK),
        ).forEach { preview ->
            assertEquals(
                AiSmartbarLayout.MESSAGE_ROWS,
                AiSmartbarLayout.mainRowCount(suggestionsOnly = true, state = AiAssistState.Translating("en", preview)),
            )
        }
    }

    @Test
    fun `a copied message's translation gets the tall bar`() {
        assertEquals(
            AiSmartbarLayout.SUGGESTION_ROWS,
            AiSmartbarLayout.mainRowCount(
                suggestionsOnly = true,
                state = AiAssistState.Translating("en", copied = CopiedTranslation("hi", targetLang = "vi")),
            ),
        )
    }

    @Test
    fun `cards show three lines on a normal phone`() {
        // 393 dp wide phone: smartbar row ~40 dp, cards get the two lower rows.
        assertEquals(3, AiSmartbarLayout.cardMaxLines(cardAreaHeightDp = 80f))
    }

    @Test
    fun `cards drop to two lines when the rows are short, never fewer`() {
        assertEquals(2, AiSmartbarLayout.cardMaxLines(cardAreaHeightDp = 66f))
        assertEquals(2, AiSmartbarLayout.cardMaxLines(cardAreaHeightDp = 20f))
    }

    @Test
    fun `cards never show more than three lines`() {
        assertEquals(3, AiSmartbarLayout.cardMaxLines(cardAreaHeightDp = 200f))
    }
    @Test
    fun `switching tone from the suggestions keeps the tall bar, a first request does not`() {
        val reply = AiAction.Reply(com.aichat.keyboard.core.network.model.Tone.FUNNY)
        assertEquals(
            AiSmartbarLayout.SUGGESTION_ROWS,
            AiSmartbarLayout.mainRowCount(true, AiAssistState.Loading(reply, fromSuggestions = true)),
        )
        assertEquals(1, AiSmartbarLayout.mainRowCount(true, AiAssistState.Loading(reply)))
    }

    @Test
    fun `failure messages get two rows so they can wrap`() {
        assertEquals(
            AiSmartbarLayout.MESSAGE_ROWS,
            AiSmartbarLayout.mainRowCount(true, AiAssistState.Failed(AiFailure.UNSUPPORTED_CHAT_APP)),
        )
        assertEquals(2, AiSmartbarLayout.MESSAGE_ROWS)
        assertEquals(1, AiSmartbarLayout.mainRowCount(false, AiAssistState.Failed(AiFailure.QUOTA)))
    }
}
