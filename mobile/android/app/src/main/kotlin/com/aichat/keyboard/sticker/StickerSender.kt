package com.aichat.keyboard.sticker

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.editorInstance
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a sticker into the focused text field as rich content (`InputConnectionCompat.commitContent`), the same
 * mechanism the clipboard uses for images. Chat apps such as Messenger, Telegram and WhatsApp accept it.
 *
 * The image is served through the app's non-exported `FileProvider`; the receiving app only gets a read grant for
 * that one URI. When the field does not accept images, text and emoji stickers are typed as text instead.
 */
object StickerSender {
    /** [TARGET_CHANGED]: the user moved to another field while the image was prepared; nothing was sent. */
    enum class Result { SENT, SENT_AS_TEXT, UNSUPPORTED, TARGET_CHANGED, FAILED }

    private const val TAG = "StickerSender"
    private const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider.file"

    /** Rendered built-in stickers. Must match the `cache-path` in `res/xml/file_paths.xml`. */
    private const val SHARE_DIR = "aichat_stickers_share"

    /** Bump when [StickerRenderer] output changes, so cached PNGs are re-rendered. */
    private const val RENDER_VERSION = 2
    private const val PNG_QUALITY = 100

    private class Prepared(val file: File, val mime: String)

    /** Must be called on the main thread (it talks to the input connection). Never throws except cancellation. */
    suspend fun send(context: Context, sticker: Sticker): Result {
        val editor = context.editorInstance().value
        val target = editor.activeInfo
        val prepared = withContext(Dispatchers.IO) {
            try {
                prepare(context, sticker)
            } catch (e: IOException) {
                Log.w(TAG, "Cannot prepare sticker ${sticker.id}", e)
                null
            } catch (e: RuntimeException) {
                // Bitmap/Canvas and asset access can throw unchecked exceptions; never crash the keyboard over one.
                Log.e(TAG, "Cannot prepare sticker ${sticker.id}", e)
                null
            }
        } ?: return Result.FAILED

        // A new FlorisEditorInfo is created for every input start: if it changed while the image was prepared,
        // the sticker would land in a different field (or chat) than the one the user tapped it for.
        if (editor.activeInfo !== target) return Result.TARGET_CHANGED

        val accepted = EditorInfoCompat.getContentMimeTypes(target.base)
        if (!StickerMime.accepts(accepted, prepared.mime)) {
            val fallback = fallbackText(sticker.source) ?: return Result.UNSUPPORTED
            editor.viInput.finishWord()
            return if (editor.commitText(fallback)) Result.SENT_AS_TEXT else Result.FAILED
        }

        val uri = try {
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, prepared.file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Sticker file is outside the FileProvider paths", e)
            return Result.FAILED
        }
        return if (editor.commitImageContent(uri, prepared.mime, sticker.description)) Result.SENT else Result.FAILED
    }

    private fun fallbackText(source: StickerSource): String? = when (source) {
        is StickerSource.Text -> source.text.replace('\n', ' ')
        is StickerSource.Emoji -> source.emoji
        is StickerSource.Asset, is StickerSource.UserFile -> null
    }

    private fun prepare(context: Context, sticker: Sticker): Prepared? = when (val source = sticker.source) {
        is StickerSource.Text, is StickerSource.Emoji -> {
            val file = File(shareDir(context), "${sticker.cacheKey}.v$RENDER_VERSION.png")
            if (!file.isFile) {
                val bitmap = StickerRenderer.render(source) ?: return null
                try {
                    writeAtomically(file) { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
                } finally {
                    bitmap.recycle()
                }
            }
            Prepared(file, StickerMime.PNG)
        }
        is StickerSource.Asset -> {
            val mime = StickerMime.forFileName(source.path) ?: return null
            // Keyed on the app version (a pack may replace an image under the same name in an update) and a hash of
            // the exact id (cacheKey folds case and symbols, so two ids can share it). The cache key comes last to
            // keep the extension, which some receiving apps use to pick a decoder.
            val file = File(
                shareDir(context),
                "asset.v${BuildConfig.VERSION_CODE}.${sha256Prefix(sticker.id)}.${sticker.cacheKey}",
            )
            if (!file.isFile) {
                writeAtomically(file) { out -> context.assets.open(source.path).use { it.copyTo(out) } }
            }
            Prepared(file, mime)
        }
        is StickerSource.UserFile -> {
            StickerRepository.get(context).userFiles.fileFor(source.id)?.let { Prepared(it, StickerMime.PNG) }
        }
    }

    private fun sha256Prefix(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(HASH_PREFIX_BYTES)
        .joinToString("") { "%02x".format(it) }

    private const val HASH_PREFIX_BYTES = 8

    private fun shareDir(context: Context): File {
        val dir = File(context.cacheDir, SHARE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        return dir
    }
}

/** Writes [file] through a temporary sibling, so a crash never leaves a half-written image that looks valid. */
internal fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
    val temp = File(file.parentFile, "${file.name}.tmp")
    try {
        temp.outputStream().use(write)
        if (!temp.renameTo(file)) throw IOException("Cannot move $temp to $file")
    } finally {
        temp.delete()
    }
}
