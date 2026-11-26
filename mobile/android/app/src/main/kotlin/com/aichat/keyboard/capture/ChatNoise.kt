/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import java.text.Normalizer
import java.util.Locale

/**
 * Recognises the non-message text of a chat screen (times, date separators, delivery and presence lines, reaction
 * badges) in English and Vietnamese, as Messenger and Zalo show them. Patterns are anchored on the whole text so a
 * message that merely contains a time ("hẹn 9h tối nhé") is kept. Forms that are also plausible messages on their own
 * ([isPositionalNoise]) are left to the parser, which checks where they sit.
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
            // "typing…" alone, or "<name> is typing" / "<name> đang soạn tin": never a bare "stop typing".
            """|(?:.{1,40}\s)?(?:is typing|đang soạn tin(?: nhắn)?)$ELLIPSIS""" +
            """|typing$ELLIPSIS""" +
            ")$",
    )

    /**
     * Text that is noise only by position: a lone day word ("Hôm nay", "Friday"), a lone time or short date ("9h30",
     * "3.50", "1-2") or a bare delivery/presence word ("Seen", "online", "read"). Any of these is also a plausible
     * short message, so the parser drops them only when they sit where the app puts its labels (centered, or small
     * and right under a bubble), never as a left or right bubble of their own.
     */
    private val positional = Regex(
        """^(?:$DAY_FULL|$TIME|\d{1,2}[/.-]\d{1,2}""" +
            """|seen|đã xem|delivered|đã nhận|sent|đã gửi|sending|đang gửi|read|đã đọc|received|online)$""",
    )

    private val trailingCount = Regex("""^(.*?)\s*\d{1,4}$""")

    /**
     * Messenger's accessibility label for a bubble: "<sender name>, <message>, double tap to see sent/receive date
     * and time, double tap and hold to react on message". The hint after the message is the anchor: without it the
     * text is an ordinary message (which may itself contain commas) and is left alone.
     */
    private val bubbleLabel = Regex(
        """^([^,\n]{1,80}), (.+?),\s*(?:double tap|double-tap|nhấn đúp|chạm hai lần|nhấn hai lần)\b.*$""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    /**
     * App-generated lines that are never something a person typed: the unread divider and "deleted/unsent a
     * message" placeholders. Messenger exposes them as full-width rows, so the centered-line check misses them.
     */
    private val systemLine = Regex(
        "^(?:" +
            """unread messages?|new messages?|tin nhắn chưa đọc|tin nhắn mới""" +
            """|(?:you|bạn|.{1,64}) (?:deleted|unsent|removed) a message""" +
            """|(?:bạn|.{1,64}) đã (?:thu hồi|gỡ|xoá|xóa) (?:một )?tin nhắn""" +
            """|this message (?:was|has been) (?:deleted|unsent|removed)|tin nhắn đã (?:bị )?(?:thu hồi|xoá|xóa)""" +
            ")$",
    )

    /**
     * Labels of on-screen controls rather than messages, e.g. the mute toggle on a video bubble ("Mute volume, not
     * pressed"): Android appends the toggle state, which no typed message ends with.
     */
    private val controlLabel = Regex(
        """^(?:.{1,60},\s*(?:not pressed|pressed|not checked|checked|đã nhấn|chưa nhấn)""" +
            """|(?:mute|unmute) (?:volume|video|audio)|(?:bật|tắt) (?:âm thanh|tiếng))$""",
    )

    /** A Messenger bubble label split into the sender's display name and the message. */
    data class BubbleLabel(val senderName: String, val content: String)

    /** Splits a Messenger bubble label ([bubbleLabel]); null for plain message text. */
    fun parseBubbleLabel(text: String): BubbleLabel? {
        val match = bubbleLabel.matchEntire(text.trim()) ?: return null
        val name = match.groupValues[1].trim()
        val content = match.groupValues[2].trim()
        if (name.isEmpty() || content.isEmpty()) return null
        return BubbleLabel(name, content)
    }

    /** The unread divider or a deleted/unsent placeholder. */
    fun isSystemLine(text: String): Boolean = systemLine.matches(normalize(text))

    /** Whose message a reply quote shows, from the header above it. */
    enum class QuotedAuthor { ME, THEM }

    private val replyHeaderEn = Regex("""^(you|.{1,64}?) replied to (you|yourself|themselves|himself|herself|.{1,64})$""")
    private val replyHeaderVi = Regex("""^(bạn|.{1,64}?) đã trả lời (bạn|chính mình|.{1,64})$""")

    /**
     * The header Messenger puts above a reply ("Thư replied to you", "You replied to Thư", "Bạn đã trả lời Thư"):
     * whose message the quote under it is. Null when [text] is not a reply header.
     */
    fun replyHeader(text: String): QuotedAuthor? {
        val normalized = normalize(text)
        replyHeaderEn.matchEntire(normalized)?.let { m ->
            val (replier, target) = m.destructured
            val quotesMe = target == "you" || (replier == "you" && target == "yourself")
            return if (quotesMe) QuotedAuthor.ME else QuotedAuthor.THEM
        }
        replyHeaderVi.matchEntire(normalized)?.let { m ->
            val (replier, target) = m.destructured
            val quotesMe = target == "bạn" || (replier == "bạn" && target == "chính mình")
            return if (quotesMe) QuotedAuthor.ME else QuotedAuthor.THEM
        }
        return null
    }

    /** The label of a control (toggle state suffix, mute buttons), not a message. */
    fun isControlLabel(text: String): Boolean = controlLabel.matches(normalize(text))

    fun isTimestampOrDate(text: String): Boolean {
        val normalized = normalize(text)
        return timestampOrDate.matches(normalized) && !dayAbbreviationAlone.matches(normalized)
    }

    /** True when [text] is a timestamp, date or status that is also a plausible message on its own. */
    fun isPositionalNoise(text: String): Boolean {
        val normalized = normalize(text)
        return positional.matches(normalized) && (isTimestampOrDate(text) || isStatus(text))
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
