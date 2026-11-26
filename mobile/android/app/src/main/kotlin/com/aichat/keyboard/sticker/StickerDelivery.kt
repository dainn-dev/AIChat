package com.aichat.keyboard.sticker

import dev.patrickgold.florisboard.ime.editor.InputAttributes

/**
 * Decides how a sticker reaches the chat. Pure Kotlin, so every case is unit-tested ([StickerSender] only executes
 * the plan).
 *
 * Order of preference:
 * 1. Rich content (`commitContent`) in the best format the field declares, retried once with another declared
 *    format if the editor refuses the first one.
 * 2. Sharing the image into the same app (`ACTION_SEND`), for apps whose chat field declares no image types
 *    (Zalo is the likely case). The user then picks the conversation in that app.
 * 3. Typing a text/emoji sticker as text, only when nothing could be shared.
 */
object StickerDelivery {
    sealed interface Attempt {
        val mime: String?

        /** `InputConnectionCompat.commitContent` with the sticker produced as [mime]. */
        data class Commit(override val mime: String) : Attempt

        /** `ACTION_SEND` of the sticker produced as [mime]. */
        data class Share(override val mime: String) : Attempt

        /** The sticker's text or emoji typed into the field. */
        data object TypeText : Attempt {
            override val mime: String? = null
        }
    }

    /** At most this many `commitContent` calls: the best format plus one retry with another accepted format. */
    const val MAX_COMMIT_ATTEMPTS = 2

    /**
     * The ordered attempts; the first that succeeds wins. An empty plan means the sticker cannot be sent here.
     *
     * @param acceptedMimes what the field declared (`EditorInfoCompat.getContentMimeTypes`); wildcards allowed.
     * @param producibleMimes the formats the sticker can be produced as, best first (see [producibleMimes]).
     * @param canShare the share intent resolves to at least one activity (the editor app, or any app via the
     *   chooser). Ignored for private fields.
     * @param isPrivateField password / `TYPE_NULL` field, or incognito mode: the image may still be committed into
     *   the field the user is typing in, but it is never handed to another activity and no text is typed.
     * @param hasTextFallback the sticker is a text or emoji sticker that can be typed as text.
     */
    fun plan(
        acceptedMimes: Array<String>?,
        producibleMimes: List<String>,
        canShare: Boolean,
        isPrivateField: Boolean,
        hasTextFallback: Boolean = false,
    ): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        producibleMimes
            .distinct()
            .filter { StickerMime.accepts(acceptedMimes, it) }
            .take(MAX_COMMIT_ATTEMPTS)
            .mapTo(attempts) { Attempt.Commit(it) }
        if (isPrivateField) return attempts

        val shareMime = shareMime(producibleMimes)
        if (canShare && shareMime != null) {
            attempts += Attempt.Share(shareMime)
        } else if (hasTextFallback) {
            attempts += Attempt.TypeText
        }
        return attempts
    }

    /**
     * The format to share in: PNG when the sticker can be produced as PNG (every chat app's share target takes it,
     * while WebP is sometimes shown as a file), else the sticker's own format (an animated GIF/WebP stays animated).
     */
    fun shareMime(producibleMimes: List<String>): String? =
        producibleMimes.firstOrNull { it == StickerMime.PNG } ?: producibleMimes.firstOrNull()

    /**
     * True for fields whose content must never leave them through another activity: password fields, raw
     * (`TYPE_NULL`) editors, and anything typed in incognito mode.
     */
    fun isPrivateField(
        type: InputAttributes.Type,
        variation: InputAttributes.Variation,
        isIncognito: Boolean,
    ): Boolean = isIncognito || type == InputAttributes.Type.NULL || variation in PASSWORD_VARIATIONS

    private val PASSWORD_VARIATIONS = setOf(
        InputAttributes.Variation.PASSWORD,
        InputAttributes.Variation.VISIBLE_PASSWORD,
        InputAttributes.Variation.WEB_PASSWORD,
    )

    /**
     * The formats a sticker can be produced as, best first.
     *
     * @param originalMime the stored file's type; null for stickers rendered on the device (text and emoji), which
     *   can be encoded as WebP (WhatsApp shows a 512x512 WebP as a real sticker) or PNG.
     * @param isAnimated the stored file is animated: it is never converted, so only its own type is offered.
     */
    fun producibleMimes(originalMime: String?, isAnimated: Boolean): List<String> = when {
        originalMime == null -> listOf(StickerMime.WEBP, StickerMime.PNG)
        isAnimated || originalMime == StickerMime.PNG -> listOf(originalMime)
        else -> listOf(originalMime, StickerMime.PNG)
    }
}
