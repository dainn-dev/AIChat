/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.reply

/**
 * The clipboard's primary clip as [ClipboardConversationProvider] sees it. [timestampMs] is wall-clock time
 * (`System.currentTimeMillis()`) of the copy; [isPlainText] is false for images, files and URIs.
 */
data class ClipSnapshot(
    val text: String?,
    val timestampMs: Long,
    val isSensitive: Boolean,
    val isPlainText: Boolean,
)

/**
 * When a copied text counts as "the message to reply to". Pure, unit-tested; never logs the clip.
 */
object ClipboardReplyPolicy {
    /** A copy older than this is probably not the message the user wants to answer now. */
    const val MAX_AGE_MS = 5 * 60 * 1000L

    /** Small tolerance for a clip time slightly ahead of ours; more means a clock change or a bad time base. */
    const val MAX_FUTURE_SKEW_MS = 5_000L

    /** `@MaxLength(4000)` on the backend's message DTO (`AiMessageDto.content`). */
    const val MAX_MESSAGE_LENGTH = 4000

    /**
     * Picks the clip to use. The keyboard's own record of the primary clip ([floris], timestamped when the copy
     * happened) wins; when the system clip has different text the keyboard missed that copy (e.g. the process was
     * not running), so the system clip is the newer one.
     */
    fun pick(floris: ClipSnapshot?, system: ClipSnapshot?): ClipSnapshot? = when {
        floris == null -> system
        system == null -> floris
        system.text != floris.text -> system
        else -> floris
    }

    /** The partner's message to reply to, or null when [clip] must not be used. */
    fun messageFrom(clip: ClipSnapshot?, nowMs: Long, fieldText: String?): String? {
        if (clip == null || !clip.isPlainText || clip.isSensitive) return null
        val age = nowMs - clip.timestampMs
        if (age > MAX_AGE_MS || age < -MAX_FUTURE_SKEW_MS) return null
        val text = clip.text?.trim().orEmpty()
        if (text.isEmpty()) return null
        // The user copied their own draft: there is no partner message in the clipboard.
        if (fieldText != null && text == fieldText.trim()) return null
        return text.capped()
    }

    private fun String.capped(): String {
        if (length <= MAX_MESSAGE_LENGTH) return this
        val end = if (Character.isHighSurrogate(this[MAX_MESSAGE_LENGTH - 1])) MAX_MESSAGE_LENGTH - 1 else MAX_MESSAGE_LENGTH
        return substring(0, end)
    }
}
