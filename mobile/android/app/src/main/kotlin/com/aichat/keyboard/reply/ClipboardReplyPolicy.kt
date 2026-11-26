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
 * When a copied text counts as "the message to reply to", and when the clipboard may be used at all. Pure,
 * unit-tested; never logs the clip.
 */
object ClipboardReplyPolicy {
    /** A copy older than this is probably not the message the user wants to answer now. */
    const val MAX_AGE_MS = 2 * 60 * 1000L

    /** Small tolerance for a clip time slightly ahead of ours; more means a clock change or a bad time base. */
    const val MAX_FUTURE_SKEW_MS = 5_000L

    /** `@MaxLength(4000)` on the backend's message DTO (`AiMessageDto.content`). */
    const val MAX_MESSAGE_LENGTH = 4000

    /**
     * Earlier reasons after which the clipboard may stand in for the chat: the chat reader is off or not connected,
     * or the app is not one it can read. After [UnavailableReason.NOTHING_FOUND] in a supported messenger, the clip
     * is most likely stale (the parser just missed the chat), so it is not used.
     */
    private val FALLBACK_REASONS = setOf(
        UnavailableReason.NOT_ENABLED,
        UnavailableReason.NOT_CONNECTED,
        UnavailableReason.UNSUPPORTED_APP,
    )

    /** Shortest run of letters, digits and token punctuation, without spaces, that is treated as a key or token. */
    private const val MIN_TOKEN_LENGTH = 20

    /** A one-time code: 4 to 8 digits, optionally grouped with spaces or dashes ("123 456", "12-34"). */
    private val oneTimeCode = Regex("""^\d(?:[ \-]?\d){3,7}$""")
    private val tokenChars = Regex("""^[A-Za-z0-9_\-.+/=]+$""")
    private val urlWithCredentials = Regex("""[a-z][a-z0-9+.\-]*://[^/\s:@]+:[^/\s@]+@""", RegexOption.IGNORE_CASE)
    private val secretUrlParam = Regex(
        """[?&#;](?:[a-z_]*token|[a-z_]*key|password|passwd|pwd|secret|sig|signature|code)=""",
        RegexOption.IGNORE_CASE,
    )
    private val email = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val passwordWord = Regex(
        """(?:^|[^\p{L}])(?:pass(?:word)?|passwd|pwd|pw|mật khẩu|mat khau)(?:$|[^\p{L}])""",
        RegexOption.IGNORE_CASE,
    )
    private val passwordAssignment = Regex(
        """(?:pass(?:word)?|passwd|pwd|mật khẩu|mat khau)\s*[:=]\s*\S+""",
        RegexOption.IGNORE_CASE,
    )

    /** "email<separator>password": an email followed by exactly one more token. */
    private val emailThenToken = Regex("""^\S+@\S+\.\S+?\s*[\s:/|,;]\s*\S+$""")

    /** Whether the clipboard may be tried after the earlier providers gave [earlier]. */
    fun mayFallBack(earlier: Collection<UnavailableReason>): Boolean = earlier.all { it in FALLBACK_REASONS }

    /**
     * True when [text] looks like a secret rather than a chat message: a one-time code, a key or token, a URL that
     * carries credentials or a secret parameter, or a login (email with a password). Such clips are never sent.
     */
    fun looksLikeSecret(text: String): Boolean {
        val t = text.trim()
        return oneTimeCode.matches(t) ||
            isTokenLike(t) ||
            urlWithCredentials.containsMatchIn(t) ||
            secretUrlParam.containsMatchIn(t) ||
            passwordAssignment.containsMatchIn(t) ||
            (email.containsMatchIn(t) && (passwordWord.containsMatchIn(t) || emailThenToken.matches(t)))
    }

    private fun isTokenLike(t: String): Boolean {
        if (t.length < MIN_TOKEN_LENGTH || !tokenChars.matches(t)) return false
        // A long laugh ("hahaha...") or a word is not a token: require digits, token punctuation or mixed case.
        val hasDigit = t.any { it.isDigit() }
        val hasPunct = t.any { it in "_-+/=." }
        val upper = t.count { it.isUpperCase() }
        val lower = t.count { it.isLowerCase() }
        return hasDigit || hasPunct || (upper >= 2 && lower >= 1)
    }

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
        if (looksLikeSecret(text)) return null
        return text.capped()
    }

    private fun String.capped(): String {
        if (length <= MAX_MESSAGE_LENGTH) return this
        val end = if (Character.isHighSurrogate(this[MAX_MESSAGE_LENGTH - 1])) MAX_MESSAGE_LENGTH - 1 else MAX_MESSAGE_LENGTH
        return substring(0, end)
    }
}
