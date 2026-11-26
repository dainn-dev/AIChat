package com.aichat.keyboard.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatNoiseTest {
    @Test
    fun `clock times in English and Vietnamese formats are timestamps`() {
        listOf("9:41", "09:41", "9:41 AM", "9:41pm", "10:30 SA", "22:15 CH", "10h30").forEach {
            assertTrue(ChatNoise.isTimestampOrDate(it), it)
        }
    }

    @Test
    fun `day words, weekdays and dates are date separators`() {
        listOf(
            "Today", "Yesterday", "Hôm nay", "HÔM QUA", "Monday", "Thứ Hai", "Chủ nhật",
            "Today at 9:41 AM", "Yesterday 21:05", "Hôm nay lúc 10:30", "10:30 Hôm nay", "T2 10:30", "Mon 9:41",
            "12/03/2026", "12/3", "12/03/2026 10:30", "12 tháng 3", "12 tháng 3, 2026", "Mar 12", "Mar 12, 2026",
            "MAR 12 AT 9:41 AM", "12 Mar 2026",
        ).forEach {
            assertTrue(ChatNoise.isTimestampOrDate(it), it)
        }
    }

    @Test
    fun `ordinary messages are not timestamps`() {
        listOf("hẹn 9h tối nhé", "ok luôn", "see you at 9:41", "today was fun", "thứ hai mình rảnh", "12").forEach {
            assertFalse(ChatNoise.isTimestampOrDate(it), it)
        }
    }

    @Test
    fun `delivery and presence statuses are recognised`() {
        listOf(
            "Seen", "Đã xem", "Delivered", "Đã nhận", "Sent", "Đã gửi", "Sending…", "Đang gửi...",
            "Seen by Minh", "Đã xem lúc 10:30", "Seen 9:41 AM",
            "Active now", "Đang hoạt động", "Active 5m ago", "Hoạt động 3 phút trước", "Vừa mới truy cập",
            "Truy cập 2 giờ trước", "Online", "typing…", "Đang soạn tin...",
        ).forEach {
            assertTrue(ChatNoise.isStatus(it), it)
        }
    }

    @Test
    fun `messages that start like a status are kept`() {
        listOf("Sent it already!", "seen that movie?", "đã xem phim chưa", "online lúc nào vậy").forEach {
            assertFalse(ChatNoise.isStatus(it), it)
        }
    }

    @Test
    fun `emoji-only text is detected, including ZWJ sequences, flags and skin tones`() {
        listOf("😂", "😂😂", "❤️", "👍🏽", "👨‍👩‍👧", "🇻🇳", " 🎉 🎉 ").forEach {
            assertTrue(ChatNoise.isEmojiOnly(it), it)
        }
        listOf("ok 👍", "haha", "", "  ", "12").forEach {
            assertFalse(ChatNoise.isEmojiOnly(it), it)
        }
    }

    @Test
    fun `an emoji followed by a count is a reaction badge`() {
        listOf("❤️ 2", "😆❤️ 3", "👍12").forEach {
            assertTrue(ChatNoise.isReactionCount(it), it)
        }
        listOf("❤️", "ok 2", "2").forEach {
            assertFalse(ChatNoise.isReactionCount(it), it)
        }
    }
    @Test
    fun `lone day words, times, short dates and bare status words need a position to count as noise`() {
        listOf(
            "Hôm nay", "today", "Chủ nhật", "Friday", "9h30", "9:41", "3.50", "1-2", "12/3",
            "online", "read", "sent", "Seen", "Đã xem",
        ).forEach {
            assertTrue(ChatNoise.isPositionalNoise(it), it)
        }
        // Unambiguous forms are noise wherever they are.
        listOf("Today at 9:41 AM", "12/03/2026", "Hôm nay lúc 10:30", "Seen by Minh", "Active 5m ago", "Mar 12").forEach {
            assertFalse(ChatNoise.isPositionalNoise(it), it)
        }
    }

    @Test
    fun `the typing pattern needs a whole word and a typing form`() {
        listOf("typing…", "Minh is typing...", "Lan đang soạn tin...", "Đang soạn tin nhắn…").forEach {
            assertTrue(ChatNoise.isStatus(it), it)
        }
        listOf("stop typing", "i hate typing", "đừng soạn tin nữa", "retyping").forEach {
            assertFalse(ChatNoise.isStatus(it), it)
        }
    }
}
