package com.aichat.keyboard.sticker

/**
 * MIME matching for rich content commits. Pure Kotlin (no `ClipDescription`), so it runs in JVM unit tests.
 */
object StickerMime {
    const val PNG = "image/png"
    const val WEBP = "image/webp"
    const val GIF = "image/gif"
    const val JPEG = "image/jpeg"

    /** The MIME type of an image file, from its extension; null if the keyboard does not send that format. */
    fun forFileName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> PNG
        "webp" -> WEBP
        "gif" -> GIF
        "jpg", "jpeg" -> JPEG
        else -> null
    }

    /** The file extension written for [mime] (so receiving apps that sniff the name pick the right decoder). */
    fun extensionFor(mime: String): String = when (mime) {
        WEBP -> "webp"
        GIF -> "gif"
        JPEG -> "jpg"
        else -> "png"
    }

    /** Bytes of a file's start that [isAnimated] needs. */
    const val HEADER_BYTES = 21

    /**
     * True if an image of type [mime] whose first bytes are [header] may be animated, so it must not be converted to
     * a static PNG. GIFs always count as animated (checking frames would need a full decode); a WebP is animated when
     * its extended header (`VP8X`) sets the animation flag.
     */
    fun isAnimated(mime: String, header: ByteArray): Boolean = when (mime) {
        GIF -> true
        WEBP -> header.size >= HEADER_BYTES &&
            header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WEBP" && header.ascii(12, 4) == "VP8X" &&
            header[WEBP_VP8X_FLAGS_OFFSET].toInt() and WEBP_ANIMATION_FLAG != 0
        else -> false
    }

    private const val WEBP_VP8X_FLAGS_OFFSET = 20
    private const val WEBP_ANIMATION_FLAG = 0x02

    private fun ByteArray.ascii(offset: Int, length: Int) = String(this, offset, length, Charsets.ISO_8859_1)

    /**
     * True if an editor that declared [accepted] (`EditorInfoCompat.getContentMimeTypes`) takes [mime].
     * Wildcards such as `image/ *` and `* / *` (without spaces) are honored. An editor that declared nothing
     * takes nothing.
     */
    fun accepts(accepted: Array<String>?, mime: String): Boolean {
        val (type, subtype) = split(mime) ?: return false
        return accepted.orEmpty().any { pattern ->
            val (patternType, patternSubtype) = split(pattern) ?: return@any false
            (patternType == "*" || patternType == type) && (patternSubtype == "*" || patternSubtype == subtype)
        }
    }

    private fun split(mime: String): Pair<String, String>? {
        val parts = mime.trim().lowercase().split('/')
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return parts[0] to parts[1]
    }
}
