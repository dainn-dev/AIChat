package com.aichat.keyboard.sticker

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import com.aichat.keyboard.share.ShareReplyActivity
import com.aichat.keyboard.sticker.StickerDelivery.Attempt
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.keyboardManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends a sticker to the focused chat. The plan comes from [StickerDelivery]:
 * 1. rich content (`InputConnectionCompat.commitContent`, the mechanism the clipboard uses for images) in the best
 *    format the field declares (Messenger, Telegram, WhatsApp, Instagram, most SMS apps);
 * 2. if the field declares no image type or refuses the commit (Zalo very likely), the image is shared into the
 *    same app with `ACTION_SEND`, or through the system chooser if that app has no share target;
 * 3. only if nothing can be shared, a text or emoji sticker is typed as text.
 *
 * The image is served through the app's non-exported `FileProvider`; the receiving app only gets a read grant for
 * that one URI. Nothing leaves a password / raw field or an incognito session through another activity.
 */
object StickerSender {
    /**
     * [SHARED]: the share screen of the chat app (or the chooser) was opened; the user picks the conversation there.
     * [TARGET_CHANGED]: the user moved to another field while the image was prepared; nothing was sent.
     */
    enum class Result { SENT, SHARED, SENT_AS_TEXT, UNSUPPORTED, TARGET_CHANGED, FAILED }

    private const val TAG = "StickerSender"
    private const val FILE_PROVIDER_AUTHORITY = "${BuildConfig.APPLICATION_ID}.provider.file"

    /** Rendered built-in stickers. Must match the `cache-path` in `res/xml/file_paths.xml`. */
    private const val SHARE_DIR = "aichat_stickers_share"

    /** Bump when [StickerRenderer] output changes, so cached images are re-rendered. */
    private const val RENDER_VERSION = 2
    private const val PNG_QUALITY = 100
    private const val WEBP_QUALITY = 100

    /** What the sticker is stored as, read once before planning (asset headers need disk access). */
    private class Origin(val mime: String?, val isAnimated: Boolean)

    /** Must be called on the main thread (it talks to the input connection). Never throws except cancellation. */
    suspend fun send(context: Context, sticker: Sticker): Result {
        val editor = context.editorInstance().value
        val target = editor.activeInfo
        val origin = io(sticker, "inspect") { origin(context, sticker) } ?: return Result.FAILED

        val accepted = EditorInfoCompat.getContentMimeTypes(target.base)
        val producible = StickerDelivery.producibleMimes(origin.mime, origin.isAnimated)
        val isPrivate = StickerDelivery.isPrivateField(
            type = target.inputAttributes.type,
            variation = target.inputAttributes.variation,
            isIncognito = context.keyboardManager().value.activeState.isIncognitoMode,
        )
        val shareMime = StickerDelivery.shareMime(producible)
        val shareTarget = if (isPrivate || shareMime == null) null else resolveShare(context, target.packageName, shareMime)
        val plan = StickerDelivery.plan(
            acceptedMimes = accepted,
            producibleMimes = producible,
            canShare = shareTarget != null,
            isPrivateField = isPrivate,
            hasTextFallback = fallbackText(sticker.source) != null,
        )
        debugLog {
            "editor=${target.packageName} accepted=${accepted.toList()} producible=$producible private=$isPrivate " +
                "share=${shareTarget?.describe()} plan=$plan"
        }
        if (plan.isEmpty()) return Result.UNSUPPORTED

        for (attempt in plan) {
            when (attempt) {
                is Attempt.Commit -> {
                    val uri = prepareUri(context, sticker, attempt.mime) ?: continue
                    // A new FlorisEditorInfo is created for every input start: if it changed while the image was
                    // prepared, the sticker would land in a different field (or chat) than the one it was meant for.
                    if (editor.activeInfo !== target) return Result.TARGET_CHANGED
                    val committed = editor.commitImageContent(uri, attempt.mime, sticker.description)
                    debugLog { "commitContent(${attempt.mime}) -> $committed" }
                    if (committed) return Result.SENT
                }
                is Attempt.Share -> {
                    // The plan only contains a share when shareTarget was resolved.
                    val share = shareTarget ?: continue
                    val uri = prepareUri(context, sticker, attempt.mime) ?: continue
                    if (editor.activeInfo !== target) return Result.TARGET_CHANGED
                    val launched = launchShare(context, share, uri, attempt.mime, sticker.description)
                    debugLog { "share(${attempt.mime}, ${share.describe()}) -> $launched" }
                    if (launched) return Result.SHARED
                }
                Attempt.TypeText -> {
                    if (editor.activeInfo !== target) return Result.TARGET_CHANGED
                    val text = fallbackText(sticker.source) ?: continue
                    editor.viInput.finishWord()
                    val typed = editor.commitText(text)
                    debugLog { "typed as text -> $typed" }
                    if (typed) return Result.SENT_AS_TEXT
                }
            }
        }
        return Result.FAILED
    }

    private fun fallbackText(source: StickerSource): String? = when (source) {
        is StickerSource.Text -> source.text.replace('\n', ' ')
        is StickerSource.Emoji -> source.emoji
        is StickerSource.Asset, is StickerSource.UserFile -> null
    }

    // ---- Diagnostics -------------------------------------------------------------------------------------------

    /** Debug builds only. Logs packages, MIME types and outcomes, never sticker content. */
    private inline fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    /** Runs [block] on the IO dispatcher; any failure is logged and becomes null (never crash the keyboard). */
    private suspend fun <T> io(sticker: Sticker, what: String, block: () -> T?): T? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            Log.w(TAG, "Cannot $what sticker ${sticker.id}", e)
            null
        } catch (e: RuntimeException) {
            // Bitmap/Canvas and asset access can throw unchecked exceptions.
            Log.e(TAG, "Cannot $what sticker ${sticker.id}", e)
            null
        }
    }

    // ---- Producing the image -----------------------------------------------------------------------------------

    private fun origin(context: Context, sticker: Sticker): Origin? = when (val source = sticker.source) {
        is StickerSource.Text, is StickerSource.Emoji -> Origin(mime = null, isAnimated = false)
        is StickerSource.Asset -> {
            val mime = StickerMime.forFileName(source.path) ?: return null
            val header = context.assets.open(source.path).use { it.readHeader() }
            Origin(mime, StickerMime.isAnimated(mime, header))
        }
        // UserStickerFiles stores every user sticker as a static PNG.
        is StickerSource.UserFile -> Origin(StickerMime.PNG, isAnimated = false)
    }

    private suspend fun prepareUri(context: Context, sticker: Sticker, mime: String): Uri? {
        val file = io(sticker, "prepare") { prepare(context, sticker, mime) } ?: return null
        return try {
            FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Sticker file is outside the FileProvider paths", e)
            null
        }
    }

    /** The sticker as a file of type [mime], from the cache when possible. Runs on the IO dispatcher. */
    private fun prepare(context: Context, sticker: Sticker, mime: String): File? = when (val source = sticker.source) {
        is StickerSource.Text, is StickerSource.Emoji -> {
            val file = File(shareDir(context), "${sticker.cacheKey}.v$RENDER_VERSION.${StickerMime.extensionFor(mime)}")
            if (!file.isFile) {
                val bitmap = StickerRenderer.render(source) ?: return null
                try {
                    writeAtomically(file) { compress(bitmap, mime, it) }
                } finally {
                    bitmap.recycle()
                }
            }
            file
        }
        is StickerSource.Asset -> {
            // Keyed on the app version (a pack may replace an image under the same name in an update) and a hash of
            // the exact id (cacheKey folds case and symbols, so two ids can share it). The cache key comes last to
            // keep the extension, which some receiving apps use to pick a decoder.
            val original = File(
                shareDir(context),
                "asset.v${BuildConfig.VERSION_CODE}.${sha256Prefix(sticker.id)}.${sticker.cacheKey}",
            )
            if (!original.isFile) {
                writeAtomically(original) { out -> context.assets.open(source.path).use { it.copyTo(out) } }
            }
            if (mime == StickerMime.forFileName(source.path)) original else convertToPng(original, mime)
        }
        is StickerSource.UserFile -> {
            val file = StickerRepository.get(context).userFiles.fileFor(source.id)
            if (mime == StickerMime.PNG) file else null
        }
    }

    /** A static image converted to PNG next to [original] (only static formats are ever planned for conversion). */
    private fun convertToPng(original: File, mime: String): File? {
        if (mime != StickerMime.PNG) return null
        val file = File(original.parentFile, "${original.name}.png")
        if (!file.isFile) {
            val bitmap = BitmapFactory.decodeFile(original.path) ?: return null
            try {
                writeAtomically(file) { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            } finally {
                bitmap.recycle()
            }
        }
        return file
    }

    private fun compress(bitmap: Bitmap, mime: String, out: OutputStream) {
        val ok = when {
            mime == StickerMime.WEBP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, WEBP_QUALITY, out)
            mime == StickerMime.WEBP ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, out)
            else -> bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        }
        if (!ok) throw IOException("Cannot encode sticker as $mime")
    }

    private fun InputStream.readHeader(): ByteArray {
        val buffer = ByteArray(StickerMime.HEADER_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return buffer.copyOf(read)
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

    // ---- Sharing -----------------------------------------------------------------------------------------------

    /** Where the share intent goes: the editor app ([packageName]), or the system chooser when null. */
    private class ShareTarget(val packageName: String?) {
        fun describe() = packageName ?: "chooser"
    }

    /**
     * The editor app when it has an `ACTION_SEND` target for [mime], else the chooser when any other app has one
     * (visible through the `<queries>` entry in the manifest), else null. This app's own share target (the
     * screenshot reply helper) never counts.
     */
    private fun resolveShare(context: Context, editorPackage: String?, mime: String): ShareTarget? {
        val pm = context.packageManager
        val own = context.packageName
        return try {
            if (editorPackage != null && editorPackage != own &&
                pm.queryActivities(Intent(Intent.ACTION_SEND).setType(mime).setPackage(editorPackage)).isNotEmpty()
            ) {
                ShareTarget(editorPackage)
            } else if (pm.queryActivities(Intent(Intent.ACTION_SEND).setType(mime))
                    .any { it.activityInfo?.packageName != own }
            ) {
                ShareTarget(null)
            } else {
                null
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot resolve share targets", e)
            null
        }
    }

    private fun PackageManager.queryActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }

    private fun launchShare(context: Context, target: ShareTarget, uri: Uri, mime: String, label: String): Boolean {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData(ClipDescription(label, arrayOf(mime)), ClipData.Item(uri))
        val intent = if (target.packageName != null) {
            send.setPackage(target.packageName)
        } else {
            // createChooser carries the ClipData and the read grant over to the chooser intent.
            Intent.createChooser(send, context.getString(R.string.aichat__stickers__pick_chat)).putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context, ShareReplyActivity::class.java)),
            )
        }
        // The keyboard is a service: the activity needs its own task.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No activity for the sticker share", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Sticker share was refused", e)
            false
        }
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
