package com.aichat.keyboard.capture

import com.aichat.keyboard.capture.ChatFixtures.me
import com.aichat.keyboard.capture.ChatFixtures.messengerComposer
import com.aichat.keyboard.capture.ChatFixtures.messengerHeader
import com.aichat.keyboard.capture.ChatFixtures.node
import com.aichat.keyboard.capture.ChatFixtures.snapshot
import com.aichat.keyboard.capture.ChatFixtures.them
import com.aichat.keyboard.capture.ChatFixtures.zaloComposer
import com.aichat.keyboard.capture.ChatFixtures.zaloHeader
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.core.network.model.MessageSender.ME
import com.aichat.keyboard.core.network.model.MessageSender.THEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatTranscriptParserTest {
    private val messenger = "com.facebook.orca"
    private val zalo = "com.zing.zalo"

    private fun parse(packageName: String, nodes: List<CapturedNode>) =
        ChatTranscriptParser.parse(snapshot(packageName, nodes))

    private fun msg(sender: MessageSender, content: String) = ChatMessage(sender, content)

    @Test
    fun `a Messenger chat becomes ordered me and them messages with the contact name`() {
        val nodes = messengerHeader + listOf(
            node("Today at 9:41 AM", left = 400, top = 320, right = 680, bottom = 360),
            them("hey, are you free tonight?", top = 380),
            me("yes! what's up", top = 500),
            // Reaction badge overlapping the bottom edge of the bubble above.
            node("❤️", left = 980, top = 570, right = 1040, bottom = 620),
            me("😂😂", top = 640),
            node("Seen", left = 950, top = 740, right = 1040, bottom = 780),
        ) + messengerComposer

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals("messenger", conversation.platform)
        assertEquals("Minh Anh", conversation.contactLabel)
        assertEquals(
            listOf(
                msg(THEM, "hey, are you free tonight?"),
                msg(ME, "yes! what's up"),
                msg(ME, "😂😂"),
            ),
            conversation.messages,
        )
    }

    @Test
    fun `a Zalo chat drops timestamps, separators and the Vietnamese statuses`() {
        val nodes = zaloHeader + listOf(
            node("10:30 Hôm nay", left = 420, top = 330, right = 660, bottom = 370),
            them("Tối nay đi ăn không?", top = 400),
            node("10:30", left = 150, top = 495, right = 260, bottom = 530),
            me("Ok luôn", top = 560),
            node("10:31", left = 930, top = 655, right = 1040, bottom = 690),
            node("Đã xem", left = 900, top = 700, right = 1040, bottom = 740),
        ) + zaloComposer

        val conversation = assertNotNull(parse(zalo, nodes))

        assertEquals("zalo", conversation.platform)
        assertEquals("Bạn Test", conversation.contactLabel)
        assertEquals(listOf(msg(THEM, "Tối nay đi ăn không?"), msg(ME, "Ok luôn")), conversation.messages)
    }

    @Test
    fun `an empty chat yields no conversation`() {
        assertNull(parse(messenger, messengerHeader + messengerComposer))
        assertNull(parse(zalo, zaloHeader + zaloComposer))
        assertNull(parse(zalo, emptyList()))
    }

    @Test
    fun `a chat with only my messages is all me`() {
        val nodes = zaloHeader + listOf(me("alo", top = 400), me("ngủ chưa", top = 520), me("🥺", top = 640)) +
            zaloComposer

        val conversation = assertNotNull(parse(zalo, nodes))

        assertEquals(listOf(ME, ME, ME), conversation.messages.map { it.sender })
        assertEquals(listOf("alo", "ngủ chưa", "🥺"), conversation.messages.map { it.content })
    }

    @Test
    fun `a very long chat keeps only the last 100 messages`() {
        val bubbles = (0 until 150).map { i ->
            val top = 300 + i * 12
            if (i % 2 == 0) them("tin $i", top = top, height = 10) else me("tin $i", top = top, height = 10)
        }
        val conversation = assertNotNull(parse(messenger, messengerHeader + bubbles))

        assertEquals(100, conversation.messages.size)
        assertEquals("tin 50", conversation.messages.first().content)
        assertEquals("tin 149", conversation.messages.last().content)
        assertEquals(ME, conversation.messages.last().sender)
    }

    @Test
    fun `emoji-only messages from both sides are kept`() {
        val nodes = messengerHeader + listOf(them("🔥", top = 400), me("👍🏽", top = 520), them("🇻🇳🇻🇳", top = 640))

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf(msg(THEM, "🔥"), msg(ME, "👍🏽"), msg(THEM, "🇻🇳🇻🇳")), conversation.messages)
    }

    @Test
    fun `password fields and editable fields are never read`() {
        val nodes = messengerHeader + listOf(
            them("mật khẩu wifi là gì", top = 400),
            node("hunter2", left = 560, top = 520, right = 1040, bottom = 610, password = true),
            node("draft not sent", left = 560, top = 640, right = 1040, bottom = 730, editable = true),
            node(left = 150, top = 760, right = 700, bottom = 850, desc = "secret desc", password = true),
        ) + messengerComposer

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf(msg(THEM, "mật khẩu wifi là gì")), conversation.messages)
        val all = conversation.messages.joinToString { it.content } + conversation.contactLabel
        assertFalse("hunter2" in all)
        assertFalse("secret desc" in all)
        assertFalse("draft" in all)
        assertFalse("Aa" in all)
    }

    @Test
    fun `an unsupported package yields no conversation`() {
        assertNull(parse("com.example.other", messengerHeader + listOf(them("hi", top = 400))))
    }

    @Test
    fun `a bubble container repeating its child's text counts once`() {
        val nodes = messengerHeader + listOf(
            node(left = 140, top = 390, right = 710, bottom = 490, desc = "hello there", className = "android.view.ViewGroup"),
            them("hello there", top = 400, height = 80),
        )

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf(msg(THEM, "hello there")), conversation.messages)
    }

    @Test
    fun `a container summarising several bubbles is dropped in favour of the bubbles`() {
        val nodes = messengerHeader + listOf(
            node(left = 0, top = 300, right = 1080, bottom = 800, desc = "hi, hello", className = "android.view.ViewGroup"),
            them("hi", top = 400),
            me("hello", top = 520),
        )

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf(msg(THEM, "hi"), msg(ME, "hello")), conversation.messages)
    }

    @Test
    fun `content descriptions of buttons and images are not messages`() {
        val nodes = messengerHeader + listOf(
            them("xem ảnh nè", top = 400),
            node(left = 150, top = 520, right = 700, bottom = 900, desc = "Photo", className = "android.widget.ImageView"),
            node(left = 560, top = 920, right = 1040, bottom = 1000, desc = "Play voice", className = "android.widget.Button"),
        )

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf(msg(THEM, "xem ảnh nè")), conversation.messages)
    }

    @Test
    fun `centered system lines are dropped`() {
        val nodes = zaloHeader + listOf(
            node("Bạn Test đã đổi ảnh nền", left = 300, top = 330, right = 780, bottom = 380),
            them("đẹp không", top = 400),
        )

        val conversation = assertNotNull(parse(zalo, nodes))

        assertEquals(listOf(msg(THEM, "đẹp không")), conversation.messages)
    }

    @Test
    fun `messages are ordered top to bottom even if the tree order differs`() {
        val nodes = messengerHeader + listOf(me("second", top = 520), them("first", top = 400))

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf("first", "second"), conversation.messages.map { it.content })
    }

    @Test
    fun `without a header the contact label is null`() {
        val conversation = assertNotNull(parse(messenger, listOf(them("hi", top = 400))))

        assertNull(conversation.contactLabel)
    }

    @Test
    fun `whitespace is trimmed and long messages and labels are capped`() {
        val longText = "a".repeat(5000)
        val longName = "N".repeat(300)
        val nodes = listOf(
            node(longName, left = 180, top = 105, right = 520, bottom = 165),
            them("  hi  \n", top = 400),
            me(longText, top = 520, height = 900),
        )

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals("hi", conversation.messages[0].content)
        assertEquals(ChatTranscriptParser.MAX_MESSAGE_CHARS, conversation.messages[1].content.length)
        assertEquals(ChatTranscriptParser.MAX_CONTACT_LABEL_CHARS, conversation.contactLabel?.length)
    }

    @Test
    fun `nodes outside the window are ignored`() {
        val nodes = messengerHeader + listOf(
            them("visible", top = 400),
            node("offscreen", left = 150, top = 2600, right = 700, bottom = 2690),
            node("zero area", left = 150, top = 700, right = 150, bottom = 700),
        )

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals(listOf("visible"), conversation.messages.map { it.content })
    }

    @Test
    fun `the presence line is never taken as the contact name`() {
        val nodes = listOf(
            node("Active now", left = 180, top = 100, right = 420, bottom = 150),
            node("Lan", left = 180, top = 150, right = 420, bottom = 200),
            them("hi", top = 400),
        )

        val conversation = assertNotNull(parse(messenger, nodes))

        assertEquals("Lan", conversation.contactLabel)
        assertTrue(conversation.messages.none { it.content == "Active now" })
    }
}
