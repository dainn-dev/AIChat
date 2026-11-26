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
 * - An input placeholder ("Aa", "Nhắn tin", "Message", ...) ends the chat only when it is in the bottom input band
 *   or sits above an open keyboard (rows of single letters); anywhere else it is an ordinary message.
 * - "Seen"/"Đã xem" receipts and centered system lines (date separators included) are not messages. A time, date or
 *   weekday ("cn", "sat", "9h30", "Hôm nay") is dropped only when centered or attached to the bubble above it as its
 *   trailing time; alone on the left or right it is a message.
 * - Lines that are close vertically and share a left edge are one bubble (text is left-aligned inside a bubble).
 * - A bubble is the user's (`me`) only when its right edge touches the right edge of the image and its left margin
 *   is the larger one; anything else (wide partner bubbles after an avatar included) is the partner's.
 */
object ChatScreenshotParser {
    /** `@ArrayMaxSize(100)` on the backend's reply DTO. */
    const val MAX_MESSAGES = 100

    /** `@MaxLength(4000)` on the backend's message DTO. */
    const val MAX_CONTENT_LENGTH = 4000

    // Full phone screenshots are taller than 1.6:1 (the vivo test device is 1260x2800 = 2.22:1).
    private const val PHONE_ASPECT = 1.8
    private const val STATUS_BAR_BOTTOM = 0.045
    private const val STATUS_BAND = 0.06
    private const val HEADER_BAND = 0.12
    // Chat screenshots may have large photo/sticker bubbles. Messenger often overlays text near y=700 in a
    // 2800px screenshot, then continues with ordinary bubbles much lower. Discard chrome only at the very bottom.
    // Full phone screenshots include the system navigation area, so the composer is around 87-92% of the image.
    private const val FULL_SCREEN_INPUT_BAND_TOP = 0.87
    private const val CROPPED_INPUT_BAND_TOP = 0.91
    private const val MESSENGER_SCREENSHOT_ASPECT = 1.85
    private const val INPUT_SEARCH_FROM = 0.5
    private const val CENTER_TOLERANCE = 0.03
    private const val CENTERED_MAX_WIDTH = 0.6
    private const val SAME_BUBBLE_GAP = 0.6
    private const val SAME_BUBBLE_LEFT = 0.05
    // The screenshot includes Android navigation/status insets; Messenger's own bubble edge leaves a larger margin
    // than the parser's original 7% threshold on this 1260px vivo screen.
    private const val ME_MAX_RIGHT_MARGIN = 0.11
    private const val KEYBOARD_ROW_MIN_KEYS = 5
    private const val MAX_NAME_LENGTH = 40
    private const val MAX_NAME_WORDS = 6

    fun parse(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int): ParsedChat {
        if (imageWidth <= 0 || imageHeight <= 0) return ParsedChat(emptyList())
        val clean = lines
            .map { it.copy(text = cleanText(it.text)) }
            .filter { it.text.isNotEmpty() && it.right > it.left && it.bottom > it.top }
            .sortedWith(compareBy({ it.top }, { it.left }))
        val isFullScreen = imageHeight >= imageWidth * MESSENGER_SCREENSHOT_ASPECT

        var body = clean
        var contactLabel: String? = null
        if (isFullScreen) {
            val headerEnd = imageHeight * HEADER_BAND
            val header = body.filter { it.top < headerEnd && !isStatusBar(it, imageHeight) }
            contactLabel = detectContactName(header)
            body = body.filter { it.top >= headerEnd && it.top < imageHeight * FULL_SCREEN_INPUT_BAND_TOP }
        }
        body = cutAtInputBar(body, imageWidth, imageHeight, fullScreen = isFullScreen)
        body = body.filterNot { isNoise(it.text) || isCenteredSystemLine(it, imageWidth) }
        body = dropTrailingTimes(body, imageWidth)
        body = dropStickerOverlayText(body, imageWidth)
        body = dropIncomingSenderLabels(body, imageWidth)
        body = dropStandaloneReactions(body, imageWidth)

        val messages = groupBubbles(body, imageWidth).map { bubble -> bubble.toMessage(imageWidth) }
        return ParsedChat(messages.takeLast(MAX_MESSAGES), contactLabel)
    }

    // -- Bands ------------------------------------------------------------------------------------------------

    private fun isStatusBar(line: OcrLine, imageHeight: Int): Boolean =
        line.bottom <= imageHeight * STATUS_BAR_BOTTOM ||
            (line.top < imageHeight * STATUS_BAND && STATUS_BAR_TEXT.matches(line.text))

    /**
     * Cuts at the input bar: a placeholder in the lower half that is in the bottom input band or has an open keyboard
     * below it. A lone "Message"/"Aa" bubble elsewhere is a message and nothing is cut.
     */
    private fun cutAtInputBar(lines: List<OcrLine>, imageWidth: Int, imageHeight: Int, fullScreen: Boolean): List<OcrLine> {
        val inputBandTop = if (fullScreen) FULL_SCREEN_INPUT_BAND_TOP else CROPPED_INPUT_BAND_TOP
        val bar = lines.withIndex().firstOrNull { (index, line) ->
            line.top >= imageHeight * INPUT_SEARCH_FROM &&
                isInputPlaceholder(line.text) &&
                (line.top >= imageHeight * inputBandTop && line.left >= imageWidth * 0.15 ||
                    lines.drop(index + 1).any { isKeyboardRow(it.text) })
        }?.value ?: return lines
        return lines.filter { it.top < bar.top }
    }

    private fun isKeyboardRow(text: String): Boolean {
        val keys = text.split(' ')
        return keys.size >= KEYBOARD_ROW_MIN_KEYS && keys.all { it.length == 1 }
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

    private fun isTimestamp(text: String): Boolean = TIMESTAMP.any { it.matches(text) }

    /**
     * Drops times/dates attached to the line above them (inside or right under the same bubble). The bubble keeps the
     * time's right edge, since an inline time is often what reaches the bubble's right side.
     */
    private fun dropTrailingTimes(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> {
        val kept = mutableListOf<OcrLine>()
        for (line in lines) {
            val previous = kept.lastOrNull()
            if (previous != null && isTimestamp(line.text) && isAttached(previous, line, imageWidth)) {
                kept[kept.lastIndex] = previous.copy(right = maxOf(previous.right, line.right))
            } else {
                kept += line
            }
        }
        return kept
    }

    private fun isAttached(previous: OcrLine, line: OcrLine, imageWidth: Int): Boolean {
        val gap = line.top - previous.bottom
        val lineHeight = (previous.height + line.height) / 2.0
        val tolerance = imageWidth * SAME_BUBBLE_LEFT
        return gap < lineHeight * SAME_BUBBLE_GAP &&
            line.left <= previous.right + tolerance &&
            line.right >= previous.left - tolerance
    }

    /** Messenger group chats show the sender name as a small, left-aligned line immediately above the bubble. */
    private fun dropIncomingSenderLabels(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> =
        lines.filterIndexed { index, line ->
            val next = lines.getOrNull(index + 1) ?: return@filterIndexed true
            val resemblesSender = looksLikeName(line.text) && line.height <= next.height * 0.85 &&
                line.left < imageWidth * 0.45 && !isNoise(line.text)
            val attachedToIncomingBubble = next.left >= line.left - imageWidth * 0.02 &&
                next.left < imageWidth * 0.50 && next.top - line.bottom in 0..(next.height * 0.8).toInt()
            !(resemblesSender && attachedToIncomingBubble)
        }

    /** OCR often reads a sticker caption or short title over a photo as if it were a chat bubble. */
    private fun dropStickerOverlayText(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> = lines.filterIndexed { index, line ->
        val previous = lines.getOrNull(index - 1)
        val next = lines.getOrNull(index + 1)
        val isShortOverlay = line.text.length in 2..40 && line.height <= maxOf(previous?.height ?: 0, next?.height ?: 0)
        val isCentredPhotoCaption = previous != null && next != null &&
            previous.bottom < line.top && next.top > line.bottom &&
            abs(line.centerX - imageWidth / 2) <= imageWidth * 0.12 &&
            abs(previous.centerX - line.centerX) <= imageWidth * 0.35 &&
            abs(next.centerX - line.centerX) <= imageWidth * 0.35
        !(isShortOverlay && isCentredPhotoCaption)
    }

    /** Reactions such as :), :V or <3 appear as a small avatar-adjacent line, not message text. */
    private fun dropStandaloneReactions(lines: List<OcrLine>, imageWidth: Int): List<OcrLine> =
        lines.filterIndexed { index, line ->
            if (!REACTION.matches(line.text)) return@filterIndexed true
            val previous = lines.getOrNull(index - 1)
            val next = lines.getOrNull(index + 1)
            val nearPrevious = previous != null && line.top - previous.bottom in 0..(imageWidth * 0.18).toInt()
            val nearNext = next != null && next.top - line.bottom in 0..(imageWidth * 0.18).toInt()
            val avatarAdjacent = line.left < imageWidth * 0.18 && (nearPrevious || nearNext)
            !avatarAdjacent
        }

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
            val rightMargin = imageWidth - right
            val isMine = rightMargin <= imageWidth * ME_MAX_RIGHT_MARGIN && left > rightMargin
            val sender = if (isMine) MessageSender.ME else MessageSender.THEM
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

    /** Times, dates and weekdays: separators when centered, trailing times when attached, otherwise messages. */
    private val TIMESTAMP: List<Regex> = listOf(
        // 10:30, 10:30 PM, 21h05
        Regex("^$CLOCK$", RegexOption.IGNORE_CASE),
        // 12/05/2026, 12/05/2026 10:30, 12 tháng 5, 12 thg 5 2026 10:30
        Regex("^$DATE(,?\\s*(lúc|at)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        Regex("^\\d{1,2}\\s+(tháng|thg)\\s+\\d{1,2}(,?\\s*\\d{4})?(,?\\s*(lúc)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        // Hôm qua 21:05, Yesterday at 9:15 AM, T2 lúc 10:30, Today
        Regex("^$DAY_WORD(,?\\s*(lúc|at)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
        Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(,?\\s*\\d{4})?" +
            "(,?\\s*(at)?\\s*$CLOCK)?$", RegexOption.IGNORE_CASE),
    )

    /** Never a message, wherever it is. */
    private val NOISE: List<Regex> = listOf(
        // Delivery / read receipts
        Regex(
            "^(seen|delivered|sent|read|sending|đã xem|đã gửi|đã nhận|đang gửi)(\\s+(by|bởi|lúc|at)\\s+.+|\\s+\\d.*)?$",
            RegexOption.IGNORE_CASE,
        ),
        // Edited / reactions labels
        Regex("^(edited|đã chỉnh sửa|đã thu hồi.*|message unsent.*|tin nhắn đã (được )?thu hồi.*)$",
            RegexOption.IGNORE_CASE),
    )

    private val REACTION = Regex("^(?::[-^]?[)D(PpVv/]|<3|😂|🤣|😭|❤️|👍|👎|😍|😢|😡)$")

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
