/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import java.text.Normalizer
import java.util.Locale

/**
 * Recognises the non-message text of a chat screen (times, date separators, delivery and presence lines, reaction
 * badges) in English and Vietnamese, as Messenger and Zalo show them. Patterns are anchored on the whole text so a
 * message that merely contains a time ("hẹn 9h tối nhé") is kept.
 */
object ChatNoise {
    private const val TIME = """\d{1,2}[:.h]\d{2}(?:\s?(?:am|pm|sa|ch|a\.m\.|p\.m\.))?"""
    private const val DAY_FULL = "today|yesterday|hôm nay|hôm qua|monday|tuesday|wednesday|thursday|friday|saturday|" +
        "sunday|thứ (?:hai|ba|tư|năm|sáu|bảy|[2-7])|chủ nhật"
    private const val DAY_ABBR = "mon|tue|wed|thu|fri|sat|sun|t[2-7]|cn"
    private const val MONTH = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|" +
        "sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private const val DATE = """\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?|\d{1,2} tháng \d{1,2}(?:,? \d{4})?|""" +
        """(?:$MONTH) \d{1,2}(?:,? \d{4})?|\d{1,2} (?:$MONTH)(?:,? \d{4})?"""
    private const val DAY_OR_DATE = """(?:(?:$DAY_FULL|$DAY_ABBR)(?:,?\s+(?:$DATE))?|(?:$DATE))"""

    private val timestampOrDate = Regex(
        """^(?:$DAY_OR_DATE(?:,?\s+(?:at|lúc))?(?:,?\s+$TIME)?|$TIME(?:,?\s+$DAY_OR_DATE)?)$""",
    )

    /** A day abbreviation alone ("cn", "sat") is too likely to be a real message. */
    private val dayAbbreviationAlone = Regex("^(?:$DAY_ABBR)$")

    private const val ELLIPSIS = """(?:\s*(?:…|\.\.\.))?"""
    private val status = Regex(
        "^(?:" +
            """(?:seen|đã xem|delivered|đã nhận|sent|đã gửi|sending|đang gửi|read|đã đọc|received)$ELLIPSIS""" +
            """(?:\s+(?:at|lúc)\s+$TIME|\s+$TIME)?""" +
            """|(?:seen|đã xem)\s+(?:by|bởi)\s+.{1,64}""" +
            """|active now|đang hoạt động|online|vừa mới truy cập|vừa truy cập""" +
            """|active \d+\s?(?:m|h|d|w|mins?|minutes?|hours?|days?|weeks?) ago""" +
            """|(?:hoạt động|truy cập) \d+ (?:phút|giờ|ngày|tuần) trước""" +
            """|.{0,40}(?:is typing|đang soạn tin|typing)$ELLIPSIS""" +
            ")$",
    )

    private val trailingCount = Regex("""^(.*?)\s*\d{1,4}$""")

    fun isTimestampOrDate(text: String): Boolean {
        val normalized = normalize(text)
        return timestampOrDate.matches(normalized) && !dayAbbreviationAlone.matches(normalized)
    }

    /** Delivery ("Seen", "Đã xem"), presence ("Active now", "Vừa mới truy cập") and typing lines. */
    fun isStatus(text: String): Boolean = status.matches(normalize(text))

    /** True if [text] has at least one emoji and nothing but emoji and whitespace. */
    fun isEmojiOnly(text: String): Boolean {
        var sawEmoji = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || isEmojiJoiner(cp) -> Unit
                isEmojiCodePoint(cp) -> sawEmoji = true
                else -> return false
            }
        }
        return sawEmoji
    }

    /** An emoji with a count next to it ("❤️ 2"): the reaction summary under a bubble. */
    fun isReactionCount(text: String): Boolean {
        val match = trailingCount.matchEntire(text.trim()) ?: return false
        return isEmojiOnly(match.groupValues[1])
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).trim().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)

    /** Variation selectors, zero-width joiner, keycap and tag characters: parts of an emoji sequence. */
    private fun isEmojiJoiner(cp: Int): Boolean =
        cp == 0xFE0F || cp == 0xFE0E || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F

    @Suppress("MagicNumber")
    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x2934 || cp == 0x2935 ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
}
