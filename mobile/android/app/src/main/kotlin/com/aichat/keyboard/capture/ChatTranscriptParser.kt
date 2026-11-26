/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.reply.CapturedConversation
import kotlin.math.abs

/**
 * Turns the visible views of a supported chat app ([SupportedMessenger]) into an ordered conversation.
 *
 * Layout heuristics, not view ids, do the work (both apps obfuscate and change their ids between versions):
 * - the top [HEADER_FRACTION] of the window is the toolbar; its first plain text line is the contact name;
 * - everything from the input field down is the composer and is ignored;
 * - a bubble that hugs the right edge (right margin under [ME_MAX_RIGHT_MARGIN] of the width, and smaller than its
 *   left margin) is the user's (`me`); anything else, including wide partner bubbles after an avatar, is the partner's;
 * - Messenger labels each bubble "<sender name>, <message>, double tap to …" ([ChatNoise.parseBubbleLabel]): only the
 *   message is kept, and every bubble with the same sender name gets the side most of that name's bubbles are on, so a
 *   bubble cut off at the screen edge cannot flip sides;
 * - the unread divider, deleted/unsent placeholders and control labels ("Mute volume, not pressed") are dropped;
 * - times, date separators, delivery/presence lines, reaction badges and centered system lines are dropped; a lone
 *   day word, time or status word ([ChatNoise.isPositionalNoise]) only when centered or attached under a bubble;
 * - editable and password views are never read.
 *
 * Returns null when nothing message-like is visible or the package is not a [SupportedMessenger].
 */
object ChatTranscriptParser {
    /** Matches the backend's message `content` limit. */
    const val MAX_MESSAGE_CHARS = 4000

    /** Matches the backend's `contact_label` limit. */
    const val MAX_CONTACT_LABEL_CHARS = 128

    /** Share of the window height, from the top, treated as the toolbar. */
    private const val HEADER_FRACTION = 0.12

    /** A line whose side margins both exceed this share of the width, and is centered, is a system line. */
    private const val CENTERED_MIN_MARGIN = 0.15
    private const val CENTERED_TOLERANCE = 0.05

    /** Own bubbles end within this share of the width from the right edge; partner bubbles leave a wider gap. */
    private const val ME_MAX_RIGHT_MARGIN = 0.06

    /** A label under a bubble is shorter than this share of the smallest bubble on screen (smaller font). */
    private const val LABEL_MAX_HEIGHT_RATIO = 0.85

    /** Largest vertical gap, as a share of the window height, between a bubble and a label attached under it. */
    private const val LABEL_MAX_GAP = 0.03

    /** "<name>, " at the start of a reply quote. */
    private val quoteName = Regex("""^([^,\n]{1,80}), \S""")

    private val nonTextClassHints = listOf("Button", "Image", "CheckBox", "Switch", "ProgressBar")
    private val titleIdHints = listOf("title", "name", "header")

    /**
     * [senderName] is set when the text was a Messenger bubble label or a reply quote ([isQuote]); a quote sits on
     * the replier's side, so it takes its side from the name (or [quotedAuthor], read from the reply header) but never
     * votes for it.
     */
    private class Line(
        val content: String,
        val node: CapturedNode,
        val senderName: String? = null,
        val isQuote: Boolean = false,
        val quotedAuthor: MessageSender? = null,
    )

    fun parse(snapshot: ChatWindowSnapshot): CapturedConversation? {
        val messenger = SupportedMessenger.fromPackage(snapshot.packageName) ?: return null
        val window = Window(snapshot)
        val readable = snapshot.nodes.filter { it.isReadable() && window.contains(it) }
        val composerTop = composerTop(snapshot.nodes, window)

        val header = readable.filter { it.boundsBottom <= window.headerBottom }
        // A bubble scrolled partly under the toolbar still counts: only its bottom has to be below the header band.
        val body = readable.filter { it.boundsBottom > window.headerBottom && it.boundsTop < composerTop }

        val lines = splitQuotes(messageLines(body, window))
        val senders = senders(lines, window)
        val messages = lines.mapIndexed { i, line ->
            ChatMessage(sender = senders[i], content = line.content.take(MAX_MESSAGE_CHARS))
        }
        if (messages.isEmpty()) return null
        return CapturedConversation(
            messages = messages.takeLast(CapturedConversation.MAX_MESSAGES),
            platform = messenger.platform,
            contactLabel = contactLabel(header),
        )
    }

    /**
     * A reply quote next to a Messenger bubble reads "<sender name>, <quoted message>" without the bubble hint. Once
     * bubble labels are on screen every real bubble carries that hint, so a hint-less line starting with a name
     * already seen on a label is a quote: it counts as that person's earlier message. A reply header right above
     * ("Thư replied to you", [ChatNoise.replyHeader]) says whose message the quote is, so then any name is accepted
     * (the user's own name is often on no bubble on screen). Headers are dropped. Without labels (Zalo, or an older
     * Messenger) nothing is split.
     */
    private fun splitQuotes(lines: List<Line>): List<Line> {
        val names = lines.mapNotNull { it.senderName }.toSet()
        if (names.isEmpty()) return lines.filter { ChatNoise.replyHeader(it.content) == null }
        val result = ArrayList<Line>(lines.size)
        var pendingAuthor: MessageSender? = null
        for (line in lines) {
            val header = ChatNoise.replyHeader(line.content)
            if (header != null) {
                pendingAuthor = if (header == ChatNoise.QuotedAuthor.ME) MessageSender.ME else MessageSender.THEM
                continue
            }
            val author = pendingAuthor
            pendingAuthor = null
            if (line.senderName != null) {
                result += line
                continue
            }
            val name = names.firstOrNull { line.content.startsWith("$it, ") }
                ?: if (author != null) quoteName.find(line.content)?.groupValues?.get(1) else null
            val quoted = name?.let { line.content.substring(it.length + 2).trim() }
            result += if (name == null || quoted.isNullOrEmpty()) {
                line
            } else {
                Line(quoted, line.node, name, isQuote = true, quotedAuthor = author)
            }
        }
        return result
    }

    /**
     * Side by position, then made consistent per sender name: a name whose bubbles are mostly on one side gets that
     * side for all of them. A tie (or no name) keeps the position-based side.
     */
    private fun senders(lines: List<Line>, window: Window): List<MessageSender> {
        val byPosition = lines.map { window.senderOf(it.node) }
        val byName = lines.indices
            .filter { lines[it].senderName != null && !lines[it].isQuote }
            .groupBy { lines[it].senderName!!.lowercase() }
            .mapValues { (_, indices) ->
                val me = indices.count { byPosition[it] == MessageSender.ME }
                val them = indices.size - me
                when {
                    me > them -> MessageSender.ME
                    them > me -> MessageSender.THEM
                    else -> null
                }
            }
        return lines.mapIndexed { i, line ->
            line.quotedAuthor ?: line.senderName?.let { byName[it.lowercase()] } ?: byPosition[i]
        }
    }

    private fun messageLines(body: List<CapturedNode>, window: Window): List<Line> {
        val candidates = body
            .mapNotNull { node -> messageText(node)?.let { toLine(it, node) } }
            .filterNot { isAlwaysNoise(it.content) || window.isCenteredSystemLine(it.node) }
        val leaves = candidates.filterNot { line -> candidates.any { it !== line && line.node.strictlyContains(it.node) } }
        val unique = leaves.filterNot { line ->
            leaves.any { other ->
                other !== line && other.content == line.content && other.node.overlaps(line.node) &&
                    other.node.area() < line.node.area()
            }
        }
        val ordered = unique.sortedWith(compareBy({ it.node.boundsTop }, { it.node.boundsLeft }))
        return dropAttachedLabels(dropReactionBadges(ordered), window)
    }

    /**
     * Drops a lone time, day or status word ([ChatNoise.isPositionalNoise]) that sits right under a bubble, inside
     * its horizontal span, and is smaller than every real bubble on screen: the time or "Seen" line the app attaches
     * to a bubble. Labels can be chained ("10:31" then "Đã xem"). The same text as a bubble of its own is kept.
     */
    private fun dropAttachedLabels(ordered: List<Line>, window: Window): List<Line> {
        val positional = ordered.map { ChatNoise.isPositionalNoise(it.content) }
        val smallestBubble = ordered.filterIndexed { i, _ -> !positional[i] }.minOfOrNull { it.node.height() }
            ?: return ordered
        val maxGap = window.height * LABEL_MAX_GAP
        val kept = ArrayList<Line>(ordered.size)
        var anchor: CapturedNode? = null
        var chainBottom = 0
        ordered.forEachIndexed { i, line ->
            val node = line.node
            val bubble = anchor
            val attached = positional[i] && bubble != null &&
                node.height() < smallestBubble * LABEL_MAX_HEIGHT_RATIO &&
                node.boundsTop - chainBottom <= maxGap &&
                node.boundsLeft < bubble.boundsRight && bubble.boundsLeft < node.boundsRight
            if (attached) {
                chainBottom = maxOf(chainBottom, node.boundsBottom)
            } else {
                kept += line
                anchor = node
                chainBottom = node.boundsBottom
            }
        }
        return kept
    }

    /** An emoji-only badge that overlaps the bubble above it is a reaction, not a message. */
    private fun dropReactionBadges(ordered: List<Line>): List<Line> {
        val kept = ArrayList<Line>(ordered.size)
        for (line in ordered) {
            val previous = kept.lastOrNull()
            val isBadge = ChatNoise.isEmojiOnly(line.content) &&
                previous != null && line.node.boundsTop < previous.node.boundsBottom
            if (!isBadge) kept += line
        }
        return kept
    }

    private fun contactLabel(header: List<CapturedNode>): String? {
        val lines = header
            .filter { node -> node.className.isTextClass() }
            .mapNotNull { node -> node.text?.clean()?.let { Line(it, node) } }
            .filterNot { isNoise(it.content) || ChatNoise.isEmojiOnly(it.content) }
            .sortedWith(compareBy({ it.node.boundsTop }, { it.node.boundsLeft }))
        val byId = lines.firstOrNull { line ->
            val id = line.node.viewIdResourceName?.substringAfter(":id/")?.lowercase().orEmpty()
            titleIdHints.any { it in id }
        }
        return (byId ?: lines.firstOrNull())?.content?.take(MAX_CONTACT_LABEL_CHARS)?.trim()?.ifEmpty { null }
    }

    private fun toLine(text: String, node: CapturedNode): Line {
        val label = ChatNoise.parseBubbleLabel(text) ?: return Line(text, node)
        return Line(label.content, node, label.senderName)
    }

    /** Text first; a content description only on views that are not buttons or images. */
    private fun messageText(node: CapturedNode): String? {
        node.text?.clean()?.let { return it }
        if (!node.className.isTextClass()) return null
        return node.contentDescription?.clean()
    }

    private fun isNoise(text: String): Boolean =
        ChatNoise.isTimestampOrDate(text) || ChatNoise.isStatus(text) || ChatNoise.isReactionCount(text)

    /** Noise wherever it is; positional noise is decided by [dropAttachedLabels] and the centered-line check. */
    private fun isAlwaysNoise(text: String): Boolean =
        (isNoise(text) && !ChatNoise.isPositionalNoise(text)) || ChatNoise.isSystemLine(text) ||
            ChatNoise.isControlLabel(text)

    /** The top of the input field (the lowest editable view in the lower half), or the window bottom. */
    private fun composerTop(nodes: List<CapturedNode>, window: Window): Int =
        nodes.filter { it.isEditable && it.boundsTop >= window.middleY }
            .minOfOrNull { it.boundsTop }
            ?: window.bottom

    private fun CapturedNode.isReadable() = !isPassword && !isEditable

    private fun String?.isTextClass(): Boolean = this == null || nonTextClassHints.none { it in this }

    private fun String.clean(): String? = trim().ifEmpty { null }

    private fun CapturedNode.height(): Int = boundsBottom - boundsTop

    private fun CapturedNode.area(): Long =
        (boundsRight - boundsLeft).toLong() * (boundsBottom - boundsTop).toLong()

    private fun CapturedNode.overlaps(other: CapturedNode): Boolean =
        boundsLeft < other.boundsRight && other.boundsLeft < boundsRight &&
            boundsTop < other.boundsBottom && other.boundsTop < boundsBottom

    private fun CapturedNode.strictlyContains(other: CapturedNode): Boolean =
        boundsLeft <= other.boundsLeft && boundsTop <= other.boundsTop &&
            boundsRight >= other.boundsRight && boundsBottom >= other.boundsBottom && area() > other.area()

    private class Window(snapshot: ChatWindowSnapshot) {
        val left = snapshot.windowLeft
        val top = snapshot.windowTop
        val right = snapshot.windowRight
        val bottom = snapshot.windowBottom
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        val middleY = (top + bottom) / 2
        val headerBottom = top + ((bottom - top) * HEADER_FRACTION).toInt()

        fun contains(node: CapturedNode): Boolean =
            node.boundsRight > node.boundsLeft && node.boundsBottom > node.boundsTop &&
                node.boundsLeft < right && node.boundsRight > left && node.boundsTop < bottom && node.boundsBottom > top

        fun senderOf(node: CapturedNode): MessageSender {
            val leftMargin = node.boundsLeft - left
            val rightMargin = right - node.boundsRight
            val hugsRight = rightMargin <= width * ME_MAX_RIGHT_MARGIN && rightMargin < leftMargin
            return if (hugsRight) MessageSender.ME else MessageSender.THEM
        }

        fun isCenteredSystemLine(node: CapturedNode): Boolean {
            val minMargin = width * CENTERED_MIN_MARGIN
            val centerOffset = abs((node.boundsLeft + node.boundsRight) / 2.0 - (left + right) / 2.0)
            return node.boundsLeft - left > minMargin && right - node.boundsRight > minMargin &&
                centerOffset <= width * CENTERED_TOLERANCE
        }
    }
}
