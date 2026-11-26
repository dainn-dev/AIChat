/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import kotlin.math.abs

/** One OCR text line and its bounding box in image pixels (origin top-left). */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

/** The chat read from a screenshot: oldest message first, and the partner's name when the header showed it. */
data class ParsedChat(
    val messages: List<ChatMessage>,
    val contactLabel: String? = null,
)

/**
 * Turns OCR lines of a chat screenshot (any app) into ordered [ChatMessage]s. Pure, no Android types.
 *
 * Layout rules, all relative to the image size so they work for any resolution:
 * - A full phone screenshot (portrait, height >= [PHONE_ASPECT] x width) has a status bar, an app header (where
 *   the partner's name is read) and an input bar at the bottom; those bands are dropped. A cropped image has none.
 * - An input placeholder ("Aa", "Nhắn tin", "Message", ...) in the lower half ends the chat: anything below it is
 *   the input bar or an open keyboard.
 * - Timestamps, date separators, "Seen"/"Đã xem" and centered system lines are not messages.
 * - Lines that are close vertically and share a left edge are one bubble (text is left-aligned inside a bubble).
 * - A bubble closer to the right edge than to the left one is the user's (`me`), otherwise the partner's.
 */
object ChatScreenshotParser {
    /** `@ArrayMaxSize(100)` on the backend's reply DTO. */
    const val MAX_MESSAGES = 100

    /** `@MaxLength(4000)` on the backend's message DTO. */
    const val MAX_CONTENT_LENGTH = 4000

    private const val PHONE_ASPECT = 1.6
    private const val STATUS_BAR_BOTTOM = 0.045
    private const val STATUS_BAND = 0.06
    private const val HEADER_BAND = 0.12
    private const val INPUT_BAND_TOP = 0.91
    private const val INPUT_SEARCH_FROM = 0.5
    private const val CENTER_TOLERANCE = 0.03
    private const val CENTERED_MAX_WIDTH = 0.6
    private const val SAME_BUBBLE_GAP = 0.6
    private const val SAME_BUBBLE_LEFT = 0.05
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_NAME_WORDS = 6

    fun parse(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int): ParsedChat {
        if (imageWidth <= 0 || imageHeight <= 0) return ParsedChat(emptyList())
        val clean = lines
            .map { it.copy(text = cleanText(it.text)) }
            .filter { it.text.isNotEmpty() && it.right > it.left && it.bottom > it.top }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val isFullScreen = imageHeight >= imageWidth * PHONE_ASPECT

        var body = clean
        var contactLabel: String? = null
        if (isFullScreen) {
            val headerEnd = imageHeight * HEADER_BAND
            val header = body.filter { it.top < headerEnd && !isStatusBar(it, imageHeight) }
            contactLabel = detectContactName(header)
            body = body.filter { it.top >= headerEnd && it.top < imageHeight * INPUT_BAND_TOP }
        }
        body = cutAtInputBar(body, imageHeight)
        body = body.filterNot { isNoise(it.text) || isCenteredSystemLine(it, imageWidth) }

        val messages = groupBubbles(body, imageWidth).map { bubble -> bubble.toMessage(imageWidth) }
        return ParsedChat(messages.takeLast(MAX_MESSAGES), contactLabel)
    }

    // -- Bands ------------------------------------------------------------------------------------------------

    private fun isStatusBar(line: OcrLine, imageHeight: Int): Boolean =
        line.bottom <= imageHeight * STATUS_BAR_BOTTOM ||
            (line.top < imageHeight * STATUS_BAND && STATUS_BAR_TEXT.matches(line.text))

    private fun cutAtInputBar(lines: List<OcrLine>, imageHeight: Int): List<OcrLine> {
        val bar = lines.firstOrNull { it.top >= imageHeight * INPUT_SEARCH_FROM && isInputPlaceholder(it.text) }
            ?: return lines
        return lines.filter { it.top < bar.top }
    }

    private fun detectContactName(header: List<OcrLine>): String? = header
        .filter { looksLikeName(it.text) }
        .maxWithOrNull(compareBy<OcrLine> { it.height }.thenByDescending { it.top })
        ?.text

    private fun looksLikeName(text: String): Boolean =
        text.length in 2..MAX_NAME_LENGTH &&
            text.split(' ').size <= MAX_NAME_WORDS &&
            text.any { it.isLetter() } &&
            !isNoise(text) &&
            !isInputPlaceholder(text) &&
            !ACTIVITY_STATUS.containsMatchIn(text) &&
            !STATUS_BAR_TEXT.matches(text)

    // -- Noise ------------------------------------------------------------------------------------------------

    private fun isNoise(text: String): Boolean = NOISE.any { it.matches(text) }

    private fun isInputPlaceholder(text: String): Boolean = INPUT_PLACEHOLDER.matches(text)

    private fun isCenteredSystemLine(line: OcrLine, imageWidth: Int): Boolean =
        abs(line.centerX - imageWidth / 2) <= imageWidth * CENTER_TOLERANCE &&
            (line.right - line.left) <= imageWidth * CENTERED_MAX_WIDTH

    // -- Bubbles ----------------------------------------------------------------------------------------------

    private class Bubble(val lines: MutableList<OcrLine>) {
        val last: OcrLine get() = lines.last()

        fun toMessage(imageWidth: Int): ChatMessage {
            val left = lines.minOf { it.left }
            val right = lines.maxOf { it.right }
            val sender = if (imageWidth - right < left) MessageSender.ME else MessageSender.THEM
            val content = lines.joinToString(" ") { it.text }.take(MAX_CONTENT_LENGTH)
            return ChatMessage(sender, content)
        }
    }

    private fun groupBubbles(lines: List<OcrLine>, imageWidth: Int): List<Bubble> {
        val bubbles = mutableListOf<Bubble>()
        for (line in lines) {
            val current = bubbles.lastOrNull()
            if (current != null && continuesBubble(current.last, line, imageWidth)) {
                current.lines += line
            } else {
                bubbles += Bubble(mutableListOf(line))
            }
        }
        return bubbles
    }

    private fun continuesBubble(previous: OcrLine, line: OcrLine, imageWidth: Int): Boolean {
        val gap = line.top - previous.bottom
        val lineHeight = (previous.height + line.height) / 2.0
        return gap < lineHeight * SAME_BUBBLE_GAP && abs(line.left - previous.left) <= imageWidth * SAME_BUBBLE_LEFT
    }

    private fun cleanText(text: String): String =
        text.filterNot { it.isISOControl() && it != '\n' && it != '\t' }
            .replace(WHITESPACE, " ")
            .trim()

    private val WHITESPACE = Regex("\\s+")

    private const val CLOCK = "\\d{1,2}[:.h]\\d{2}(\\s?(am|pm|sa|ch))?"
    private const val DATE = "\\d{1,2}[/.-]\\d{1,2}([/.-]\\d{2,4})?"
    private const val DAY_WORD = "(hôm nay|hôm qua|today|yesterday|thứ\\s?[2-7]|thứ\\s(hai|ba|tư|năm|sáu|bảy)|" +
        "chủ nhật|cn|t[2-7]|mon(day)?|tue(sday)?|wed(nesday)?|thu(rsday)?|fri(day)?|sat(urday)?|sun(day)?)\\.?"

    private val NOISE: List<Regex> = listOf(
        // 10:30, 10:30 PM, 21h05
        Regex("^$CLOCK$", RegexOption.IGNORE_CASE),
        // 12/05/2026, 12/05/2026 10:30, 12 tháng 5, 12 thg 5 2026 10:30
        Regex("^$DATE(,?\\s*(lúc|at)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        Regex("^\\d{1,2}\\s+(tháng|thg)\\s+\\d{1,2}(,?\\s*\\d{4})?(,?\\s*(lúc)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        // Hôm qua 21:05, Yesterday at 9:15 AM, T2 lúc 10:30, Today
        Regex("^$DAY_WORD(,?\\s*(lúc|at)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(,?\\s*\\d{4})?" +
            "(,?\\s*(at)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        // Delivery / read receipts
        Regex(
            "^(seen|delivered|sent|read|sending|đã xem|đã gửi|đã nhận|đang gửi)(\\s+(by|bởi|lúc|at)\\s+.+|\\s+\\d.*)?$",
            RegexOption.IGNORE_CASE,
        ),
        // Edited / reactions labels
        Regex("^(edited|đã chỉnh sửa|đã thu hồi.*|message unsent.*|tin nhắn đã (được )?thu hồi.*)$",
            RegexOption.IGNORE_CASE),
    )

    private val ACTIVITY_STATUS = Regex(
        "(active|online|offline|typing|đang hoạt động|đang nhập|hoạt động|truy cập|vừa mới|phút trước|giờ trước|" +
            "minutes? ago|hours? ago|last seen)",
        RegexOption.IGNORE_CASE,
    )

    private val STATUS_BAR_TEXT = Regex(
        "^($CLOCK|\\d{1,3}\\s?%|lte|4g|5g|3g|volte|wi-?fi|[<←‹]|\\W+)$",
        RegexOption.IGNORE_CASE,
    )

    private val INPUT_PLACEHOLDER = Regex(
        "^(aa|messages?|type a message|write a message|nhắn tin|tin nhắn|soạn tin nhắn|nhập tin nhắn|" +
            "imessage|text message)\\s*(\\.\\.\\.|…)?$",
        RegexOption.IGNORE_CASE,
    )
}
