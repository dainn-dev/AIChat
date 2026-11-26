package com.aichat.keyboard.sticker

import java.io.File
import java.util.UUID

/**
 * The stickers a user added from their gallery: one PNG per sticker, named `<uuid>.png`, in [dir]
 * (app-private storage, `filesDir/aichat_stickers/user`). They never leave the device except when the user sends one.
 *
 * Plain `java.io` so it is unit-tested on the JVM. Ids are validated before any path is built, so a caller can never
 * reach a file outside [dir].
 */
class UserStickerFiles(
    val dir: File,
    private val maxStickers: Int = MAX_USER_STICKERS,
) {
    /** Sticker files, newest first. Anything that is not `<uuid>.png` is ignored. */
    fun list(): List<File> = dir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == EXTENSION && isValidId(it.nameWithoutExtension) }
        .sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })

    fun isFull(): Boolean = list().size >= maxStickers

    /** A fresh, not yet existing file for a new sticker, or null when the store is full. */
    fun newStickerFile(): File? {
        if (isFull()) return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, "${UUID.randomUUID()}.$EXTENSION")
    }

    /** The file of sticker [id], or null if the id is malformed or there is no such sticker. */
    fun fileFor(id: String): File? {
        if (!isValidId(id)) return null
        return File(dir, "$id.$EXTENSION").takeIf { it.isFile }
    }

    fun delete(id: String): Boolean = fileFor(id)?.delete() ?: false

    companion object {
        const val MAX_USER_STICKERS = 200
        const val EXTENSION = "png"

        private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun isValidId(id: String): Boolean = ID.matches(id)
    }
}
