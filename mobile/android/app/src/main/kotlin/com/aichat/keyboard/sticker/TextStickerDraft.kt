package com.aichat.keyboard.sticker

/**
 * Text rules of a user-made text sticker ([TextStickerMakerActivity]). Pure Kotlin so they are unit-tested.
 *
 * [StickerRenderer] breaks lines only at `\n` and shrinks the font to fit the widest line, so a long single line
 * would come out tiny: [layout] wraps it at spaces into at most [MAX_LINES] lines.
 */
object TextStickerDraft {
    const val MAX_CHARS = 40
    const val MAX_LINES = 3

    /** Lines longer than this are wrapped by [layout]; matches the longest built-in phrase lines. */
    const val WRAP_CHARS = 10

    /** Whether the text field may take [value]: at most [MAX_CHARS] characters and [MAX_LINES] lines. */
    fun accepts(value: String): Boolean = value.length <= MAX_CHARS && value.count { it == '\n' } < MAX_LINES

    /** Whether [value] makes a sticker (anything but whitespace). */
    fun isSavable(value: String): Boolean = value.isNotBlank() && accepts(value)

    /**
     * The sticker text for [value]: lines trimmed, blank lines dropped and long lines wrapped at spaces. The user's
     * own line breaks always stay. Lines are wrapped at [WRAP_CHARS], or wider when that would make more than
     * [MAX_LINES] lines.
     */
    fun layout(value: String): String {
        val typed = value.lines().map { it.trim().replace(SPACES, " ") }.filter { it.isNotEmpty() }
        if (typed.isEmpty()) return ""
        val longest = typed.maxOf { it.length }
        var width = WRAP_CHARS
        while (true) {
            val wrapped = typed.flatMap { wrap(it, width) }
            // At the longest line's length nothing wraps any more, so the loop always ends.
            if (wrapped.size <= MAX_LINES || width >= longest) return wrapped.joinToString("\n")
            width++
        }
    }

    /** Greedy wrap of one line at spaces; a single word longer than [width] stays on its own line. */
    private fun wrap(line: String, width: Int): List<String> {
        if (line.length <= width) return listOf(line)
        val lines = mutableListOf<String>()
        var current = ""
        for (word in line.split(' ')) {
            current = when {
                current.isEmpty() -> word
                current.length + 1 + word.length <= width -> "$current $word"
                else -> {
                    lines += current
                    word
                }
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private val SPACES = Regex("\\s+")
}
