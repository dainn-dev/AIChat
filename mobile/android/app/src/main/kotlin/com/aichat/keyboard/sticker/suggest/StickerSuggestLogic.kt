/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.suggest

import com.aichat.keyboard.sticker.Sticker
import com.aichat.keyboard.sticker.StickerPack
import com.aichat.keyboard.sticker.StickerTag

/** Pure decisions behind sticker suggestions (typing and AI replies). Unit-tested. */
object StickerSuggestLogic {
    /** Thumbnails shown at most, for typing and for AI replies. */
    const val MAX_STICKERS = 3

    /**
     * Send results that mean the sticker reached the chat. Compared by name so a result added later to
     * `StickerSender.Result` (e.g. `SHARED`) works without touching this code.
     */
    private val SUCCESS_RESULTS = setOf("SENT", "SENT_AS_TEXT", "SHARED")

    fun isSuccess(resultName: String): Boolean = resultName in SUCCESS_RESULTS

    /**
     * Whether typing suggestions may be shown: never in password fields, raw (TYPE_NULL) editors or incognito mode,
     * where the typed text must not drive anything beyond plain typing.
     */
    fun isAllowed(enabled: Boolean, isRawInputEditor: Boolean, isPasswordField: Boolean, isIncognito: Boolean): Boolean =
        enabled && !isRawInputEditor && !isPasswordField && !isIncognito

    /**
     * Up to [limit] stickers tagged with [tags]: tag order first, then pack order (built-in packs come first in
     * [packs]), then sticker order. Deterministic; each sticker at most once.
     */
    fun pick(tags: List<StickerTag>, packs: List<StickerPack>, limit: Int = MAX_STICKERS): List<Sticker> {
        if (tags.isEmpty() || limit <= 0) return emptyList()
        val stickers = packs.flatMap { it.stickers }
        val picked = LinkedHashMap<String, Sticker>()
        for (tag in tags.distinct()) {
            for (sticker in stickers) {
                if (picked.size >= limit) return picked.values.toList()
                if (tag in sticker.tags && sticker.id !in picked) picked[sticker.id] = sticker
            }
        }
        return picked.values.toList()
    }

    /** Wire names from the backend's `sticker_tags` to known tags, unknown ones dropped, at most [MAX_STICKERS]. */
    fun tagsFromWire(wire: List<String>): List<StickerTag> =
        wire.mapNotNull { StickerTag.fromWire(it) }.distinct().take(MAX_STICKERS)

    /**
     * How many characters to delete before the cursor after a sticker was sent from a typing suggestion: the length
     * of [trigger] if it is still exactly before the cursor as a whole word, else 0 (the user typed on, moved the
     * cursor, or selected text, so the text must be left alone).
     *
     * [textBeforeCursor] must be at least `trigger.length + 1` characters when the field has that much, so the
     * character before the trigger can be checked.
     */
    fun charsToDelete(trigger: String, textBeforeCursor: String, hasSelection: Boolean): Int {
        if (trigger.isEmpty() || hasSelection || !textBeforeCursor.endsWith(trigger)) return 0
        val before = textBeforeCursor.getOrNull(textBeforeCursor.length - trigger.length - 1)
        if (before != null && before.isLetterOrDigit()) return 0
        return trigger.length
    }
}
