package com.aichat.keyboard.share

import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender.ME
import com.aichat.keyboard.core.network.model.MessageSender.THEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic layouts only: a 1080x2400 portrait phone screenshot, lines ~50 px tall, bubbles from the partner
 * on the left (x ~ 150..), the user's on the right (.. ~1030).
 */
class ChatScreenshotParserTest {
    private val w = 1080
    private val h = 2400

    private fun line(text: String, left: Int, top: Int, right: Int, height: Int = 50) =
        OcrLine(text, left, top, right, top + height)

    private fun them(text: String, top: Int, right: Int = 600) = line(text, 150, top, right)
    private fun me(text: String, top: Int, left: Int = 600) = line(text, left, top, 1030)

    private fun parse(vararg lines: OcrLine, width: Int = w, height: Int = h) =
        ChatScreenshotParser.parse(lines.toList(), width, height)

    @Test
    fun `left bubbles are them and right bubbles are me, in vertical order`() {
        val chat = parse(
            them("Tối nay rảnh không?", top = 600),
            me("Rảnh nè", top = 760),
            them("Đi cà phê nha", top = 920),
        )
        assertEquals(
            listOf(
                ChatMessage(THEM, "Tối nay rảnh không?"),
                ChatMessage(ME, "Rảnh nè"),
                ChatMessage(THEM, "Đi cà phê nha"),
            ),
            chat.messages,
        )
    }

    @Test
    fun `lines given out of order are sorted top to bottom`() {
        val chat = parse(me("second", top = 900), them("first", top = 600))
        assertEquals(listOf("first", "second"), chat.messages.map { it.content })
    }

    @Test
    fun `wrapped lines of one bubble are merged with a space`() {
        val chat = parse(
            them("Hôm qua mình đi xem phim", top = 600, right = 760),
            them("vui lắm luôn đó", top = 660, right = 520),
            me("Phim gì vậy?", top = 820),
        )
        assertEquals(
            listOf(
                ChatMessage(THEM, "Hôm qua mình đi xem phim vui lắm luôn đó"),
                ChatMessage(ME, "Phim gì vậy?"),
            ),
            chat.messages,
        )
    }

    @Test
    fun `a short last line of a wide right bubble stays me`() {
        val chat = parse(
            line("Mình nghĩ là thứ bảy sẽ hợp hơn vì", left = 330, top = 600, right = 1030),
            line("ok?", left = 330, top = 660, right = 410),
        )
        assertEquals(listOf(ChatMessage(ME, "Mình nghĩ là thứ bảy sẽ hợp hơn vì ok?")), chat.messages)
    }

    @Test
    fun `two bubbles of the same sender separated by a bubble gap stay separate`() {
        val chat = parse(
            them("Alo", top = 600),
            them("Có đó không", top = 700),
        )
        assertEquals(listOf("Alo", "Có đó không"), chat.messages.map { it.content })
    }

    @Test
    fun `status bar is dropped and the header name becomes the contact label`() {
        val chat = parse(
            line("9:41", left = 60, top = 20, right = 160, height = 40),
            line("85%", left = 900, top = 20, right = 1000, height = 40),
            line("Linh Nguyễn", left = 200, top = 120, right = 520, height = 60),
            line("Đang hoạt động", left = 200, top = 190, right = 470, height = 36),
            them("Hello", top = 600),
        )
        assertEquals("Linh Nguyễn", chat.contactLabel)
        assertEquals(listOf(ChatMessage(THEM, "Hello")), chat.messages)
    }

    @Test
    fun `an activity line alone in the header is not taken as a name`() {
        val chat = parse(
            line("Active now", left = 200, top = 150, right = 420, height = 36),
            them("Hi", top = 600),
        )
        assertNull(chat.contactLabel)
        assertEquals(listOf("Hi"), chat.messages.map { it.content })
    }

    @Test
    fun `timestamps, date separators and seen markers are dropped`() {
        val chat = parse(
            line("Hôm qua 21:05", left = 420, top = 450, right = 660, height = 36),
            them("Ngủ chưa", top = 600),
            line("10:30", left = 160, top = 660, right = 260, height = 30),
            me("Chưa nè", top = 800),
            line("Đã xem", left = 880, top = 900, right = 1030, height = 30),
            line("Seen", left = 930, top = 1000, right = 1030, height = 30),
            line("12/05/2026", left = 440, top = 1100, right = 640, height = 36),
            // A trailing time attached under the user's bubble.
            line("10:45 PM", left = 900, top = 855, right = 1030, height = 30),
            line("Yesterday at 9:15 AM", left = 380, top = 1300, right = 700, height = 36),
        )
        assertEquals(listOf(ChatMessage(THEM, "Ngủ chưa"), ChatMessage(ME, "Chưa nè")), chat.messages)
    }

    @Test
    fun `messages that only start like a receipt, placeholder or weekday are kept`() {
        val chat = parse(
            them("Seen that movie?", top = 600),
            me("Tin nhắn của bạn dễ thương ghê", top = 1500),
            them("Sunny", top = 1700),
        )
        assertEquals(
            listOf("Seen that movie?", "Tin nhắn của bạn dễ thương ghê", "Sunny"),
            chat.messages.map { it.content },
        )
    }

    @Test
    fun `centered system lines are dropped`() {
        val chat = parse(
            line("Các bạn đã kết bạn trên Zalo", left = 300, top = 500, right = 780, height = 36),
            them("Chào bạn", top = 600),
        )
        assertEquals(listOf("Chào bạn"), chat.messages.map { it.content })
    }

    @Test
    fun `the input bar and everything below it (an open keyboard) are dropped`() {
        val chat = parse(
            them("Mai gặp nha", top = 900),
            line("Aa", left = 300, top = 1300, right = 360),
            line("q w e r t y u i o p", left = 20, top = 1500, right = 1060),
            line("a s d f g h j k l", left = 60, top = 1650, right = 1020),
        )
        assertEquals(listOf("Mai gặp nha"), chat.messages.map { it.content })
    }

    @Test
    fun `the bottom band of a full screenshot is treated as the input bar`() {
        val chat = parse(
            them("Ok", top = 1800),
            line("Nhắn tin", left = 250, top = 2250, right = 420),
        )
        assertEquals(listOf("Ok"), chat.messages.map { it.content })
    }

    @Test
    fun `a placeholder-like message in the lower half is kept with everything below it`() {
        val chat = parse(
            them("Ok", top = 1300),
            them("Message", top = 1450),
            line("Aa", left = 950, top = 1600, right = 1030),
            them("Tin nhắn", top = 2000),
            them("still a message", top = 2100),
        )
        assertEquals(
            listOf(
                ChatMessage(THEM, "Ok"),
                ChatMessage(THEM, "Message"),
                ChatMessage(ME, "Aa"),
                ChatMessage(THEM, "Tin nhắn"),
                ChatMessage(THEM, "still a message"),
            ),
            chat.messages,
        )
    }

    @Test
    fun `a placeholder in the bottom band of a cropped image is the input bar`() {
        val chat = ChatScreenshotParser.parse(
            listOf(
                OcrLine("Hi", 150, 100, 400, 150),
                OcrLine("Aa", 300, 930, 360, 980),
            ),
            imageWidth = 1080,
            imageHeight = 1000,
        )
        assertEquals(listOf("Hi"), chat.messages.map { it.content })
    }

    @Test
    fun `lone left or right aligned weekday, time and date words are messages`() {
        val chat = parse(
            them("cn", top = 600, right = 210),
            line("sat", left = 960, top = 760, right = 1030),
            them("9h30", top = 920, right = 260),
            line("Hôm nay", left = 860, top = 1080, right = 1030),
            them("T7", top = 1240, right = 200),
            line("12/05", left = 920, top = 1400, right = 1030),
        )
        assertEquals(
            listOf(
                ChatMessage(THEM, "cn"),
                ChatMessage(ME, "sat"),
                ChatMessage(THEM, "9h30"),
                ChatMessage(ME, "Hôm nay"),
                ChatMessage(THEM, "T7"),
                ChatMessage(ME, "12/05"),
            ),
            chat.messages,
        )
    }

    @Test
    fun `centered weekday separators and inline bubble times are dropped`() {
        val chat = parse(
            line("T2", left = 510, top = 500, right = 570, height = 36),
            line("Ok nha", left = 700, top = 600, right = 900),
            line("21:05", left = 920, top = 615, right = 1030, height = 30),
            them("Sun", top = 800, right = 230),
            line("CN 10:30", left = 470, top = 950, right = 610, height = 36),
        )
        assertEquals(listOf(ChatMessage(ME, "Ok nha"), ChatMessage(THEM, "Sun")), chat.messages)
    }

    @Test
    fun `a wide partner bubble after an avatar is them`() {
        val chat = parse(
            line("Một tin nhắn rất dài của bạn kia, gần chạm", left = 170, top = 600, right = 950),
            line("mép phải màn hình luôn", left = 170, top = 660, right = 640),
        )
        assertEquals(
            listOf(ChatMessage(THEM, "Một tin nhắn rất dài của bạn kia, gần chạm mép phải màn hình luôn")),
            chat.messages,
        )
    }

    @Test
    fun `a wide own bubble touching the right edge is me`() {
        val chat = parse(line("Tin nhắn của mình dài gần hết chiều ngang", left = 140, top = 600, right = 1030))
        assertEquals(listOf(ChatMessage(ME, "Tin nhắn của mình dài gần hết chiều ngang")), chat.messages)
    }

    @Test
    fun `a mid-screen bubble away from the right edge is them`() {
        val chat = parse(line("hmm", left = 700, top = 600, right = 900))
        assertEquals(listOf(ChatMessage(THEM, "hmm")), chat.messages)
    }

    @Test
    fun `a cropped screenshot keeps its top lines and detects no contact`() {
        val chat = ChatScreenshotParser.parse(
            listOf(
                OcrLine("Tối nay đi ăn không?", 150, 10, 600, 60),
                OcrLine("ok luôn", 700, 120, 1030, 170),
            ),
            imageWidth = 1080,
            imageHeight = 400,
        )
        assertNull(chat.contactLabel)
        assertEquals(
            listOf(ChatMessage(THEM, "Tối nay đi ăn không?"), ChatMessage(ME, "ok luôn")),
            chat.messages,
        )
    }

    @Test
    fun `keeps only the last 100 messages`() {
        val lines = (0 until 120).map { i ->
            val top = 400 + i * 15
            if (i % 2 == 0) {
                OcrLine("m$i", 150, top, 400, top + 10)
            } else {
                OcrLine("m$i", 800, top, 1030, top + 10)
            }
        }
        val chat = ChatScreenshotParser.parse(lines, imageWidth = 1080, imageHeight = 3000)
        assertEquals(ChatScreenshotParser.MAX_MESSAGES, chat.messages.size)
        assertEquals("m119", chat.messages.last().content)
        assertEquals("m20", chat.messages.first().content)
    }

    @Test
    fun `long messages are truncated to the backend limit`() {
        val chat = parse(them("x".repeat(5000), top = 600))
        assertEquals(ChatScreenshotParser.MAX_CONTENT_LENGTH, chat.messages.single().content.length)
    }

    @Test
    fun `blank lines, control characters and invalid sizes yield nothing`() {
        assertTrue(parse(them("   ", top = 600)).messages.isEmpty())
        assertTrue(ChatScreenshotParser.parse(listOf(them("Hi", top = 600)), 0, 0).messages.isEmpty())
        assertEquals("ab", parse(them("a\u0000b", top = 600)).messages.single().content)
    }

    @Test
    fun `no lines gives an empty chat`() {
        val chat = parse()
        assertTrue(chat.messages.isEmpty())
        assertNull(chat.contactLabel)
    }
}
