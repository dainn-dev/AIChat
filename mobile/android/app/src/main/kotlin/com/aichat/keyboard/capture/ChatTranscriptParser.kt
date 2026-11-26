/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.reply.CapturedConversation
import kotlin.math.abs

/**
 * Turns the visible views of a Messenger or Zalo chat into an ordered conversation.
 *
 * Layout heuristics, not view ids, do the work (both apps obfuscate and change their ids between versions):
 * - the top [HEADER_FRACTION] of the window is the toolbar; its first plain text line is the contact name;
 * - everything from the input field down is the composer and is ignored;
 * - a bubble closer to the right edge than to the left edge is the user's (`me`), otherwise the partner's;
 * - times, date separators, delivery/presence lines, reaction badges and centered system lines are dropped;
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

    private val nonTextClassHints = listOf("Button", "Image", "CheckBox", "Switch", "ProgressBar")
    private val titleIdHints = listOf("title", "name", "header")

    private class Line(val content: String, val node: CapturedNode)

    fun parse(snapshot: ChatWindowSnapshot): CapturedConversation? {
        val messenger = SupportedMessenger.fromPackage(snapshot.packageName) ?: return null
        val window = Window(snapshot)
        val readable = snapshot.nodes.filter { it.isReadable() && window.contains(it) }
        val composerTop = composerTop(snapshot.nodes, window)

        val header = readable.filter { it.boundsBottom <= window.headerBottom }
        val body = readable.filter { it.boundsTop >= window.headerBottom - 1 && it.boundsTop < composerTop }

        val messages = messageLines(body, window).map { line ->
            ChatMessage(sender = window.senderOf(line.node), content = line.content.take(MAX_MESSAGE_CHARS))
        }
        if (messages.isEmpty()) return null
        return CapturedConversation(
            messages = messages.takeLast(CapturedConversation.MAX_MESSAGES),
            platform = messenger.platform,
            contactLabel = contactLabel(header),
        )
    }

    private fun messageLines(body: List<CapturedNode>, window: Window): List<Line> {
        val candidates = body
            .mapNotNull { node -> messageText(node)?.let { Line(it, node) } }
            .filterNot { isNoise(it.content) || window.isCenteredSystemLine(it.node) }
        val leaves = candidates.filterNot { line -> candidates.any { it !== line && line.node.strictlyContains(it.node) } }
        val unique = leaves.filterNot { line ->
            leaves.any { other ->
                other !== line && other.content == line.content && other.node.overlaps(line.node) &&
                    other.node.area() < line.node.area()
            }
        }
        val ordered = unique.sortedWith(compareBy({ it.node.boundsTop }, { it.node.boundsLeft }))
        return dropReactionBadges(ordered)
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

    /** Text first; a content description only on views that are not buttons or images. */
    private fun messageText(node: CapturedNode): String? {
        node.text?.clean()?.let { return it }
        if (!node.className.isTextClass()) return null
        return node.contentDescription?.clean()
    }

    private fun isNoise(text: String): Boolean =
        ChatNoise.isTimestampOrDate(text) || ChatNoise.isStatus(text) || ChatNoise.isReactionCount(text)

    /** The top of the input field (the lowest editable view in the lower half), or the window bottom. */
    private fun composerTop(nodes: List<CapturedNode>, window: Window): Int =
        nodes.filter { it.isEditable && it.boundsTop >= window.middleY }
            .minOfOrNull { it.boundsTop }
            ?: window.bottom

    private fun CapturedNode.isReadable() = !isPassword && !isEditable

    private fun String?.isTextClass(): Boolean = this == null || nonTextClassHints.none { it in this }

    private fun String.clean(): String? = trim().ifEmpty { null }

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
        val middleY = (top + bottom) / 2
        val headerBottom = top + ((bottom - top) * HEADER_FRACTION).toInt()

        fun contains(node: CapturedNode): Boolean =
            node.boundsRight > node.boundsLeft && node.boundsBottom > node.boundsTop &&
                node.boundsLeft < right && node.boundsRight > left && node.boundsTop < bottom && node.boundsBottom > top

        fun senderOf(node: CapturedNode): MessageSender {
            val leftMargin = node.boundsLeft - left
            val rightMargin = right - node.boundsRight
            return if (rightMargin < leftMargin) MessageSender.ME else MessageSender.THEM
        }

        fun isCenteredSystemLine(node: CapturedNode): Boolean {
            val minMargin = width * CENTERED_MIN_MARGIN
            val centerOffset = abs((node.boundsLeft + node.boundsRight) / 2.0 - (left + right) / 2.0)
            return node.boundsLeft - left > minMargin && right - node.boundsRight > minMargin &&
                centerOffset <= width * CENTERED_TOLERANCE
        }
    }
}
