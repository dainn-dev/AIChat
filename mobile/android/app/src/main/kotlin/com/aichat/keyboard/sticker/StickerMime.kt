package com.aichat.keyboard.sticker

/**
 * MIME matching for rich content commits. Pure Kotlin (no `ClipDescription`), so it runs in JVM unit tests.
 */
object StickerMime {
    const val PNG = "image/png"

    /** The MIME type of an image file, from its extension; null if the keyboard does not send that format. */
    fun forFileName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> PNG
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> null
    }

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
