package com.aichat.keyboard.theme.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.aichat.keyboard.sticker.StickerImageMath
import com.aichat.keyboard.sticker.writeAtomically
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The keyboard background photo: imported from the gallery into app-private storage
 * (`filesDir/keyboard_background/bg-<uuid>.jpg`) and referenced by `prefs.aichatKeyboardBackground.imageFile`.
 *
 * A new import always gets a new file name, so the keyboard (and Coil's cache) never sees a half-replaced image:
 * the file is written first, then the pref switches to it, then older files are deleted.
 */
object KeyboardBackgroundStore {
    private const val TAG = "KeyboardBackground"

    /** Directory under `filesDir`, also the path segment of the `flex:/` URI the theme overlay uses. */
    const val URI_DIR = "aichat-bg"
    private const val DIR = "keyboard_background"

    /** Refuse decoding anything larger (e.g. a 200 MP panorama), so a single image cannot exhaust memory. */
    private const val MAX_SOURCE_PIXELS = 50_000_000L

    /** Decoded at least this big on both sides where the photo allows it: about a phone screen width. */
    private const val MIN_SIDE_PX = 1080

    /** Stored at most this big on the long side: sharp on any keyboard, small enough to decode in the IME. */
    private const val MAX_SIDE_PX = 2048
    private const val JPEG_QUALITY = 90

    private val FILE_NAME = Regex("""^bg-[0-9a-f-]{36}\.jpg$""")

    fun isValidFileName(name: String): Boolean = FILE_NAME.matches(name)

    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** The stored file for [name], or null if [name] is not one of ours. */
    fun fileOf(context: Context, name: String): File? =
        if (isValidFileName(name)) File(dir(context), name) else null

    /**
     * The absolute path for a background asset URI (`flex:/aichat-bg/<file>`), for `FlorisAssetResolver`.
     * Null if [uri] is not a background URI; throws if it is one but the file is invalid or gone.
     */
    fun resolveAssetPath(context: Context, uri: String): String? {
        if (!uri.startsWith(KeyboardBackgroundOverlay.URI_PREFIX)) return null
        val name = uri.removePrefix(KeyboardBackgroundOverlay.URI_PREFIX)
        val file = checkNotNull(fileOf(context, name)) { "Invalid keyboard background file name '$name'" }
        check(file.isFile) { "Keyboard background '$name' does not exist" }
        return file.path
    }

    enum class Outcome { SET, INVALID }

    /** Imports [uri] and makes it the keyboard background. */
    suspend fun setFromUri(context: Context, uri: Uri): Outcome = withContext(Dispatchers.IO) {
        val name = try {
            importOrThrow(context, uri)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot import background image", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No read access to the picked image", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to import the picked image", e)
            null
        } ?: return@withContext Outcome.INVALID
        val prefs by FlorisPreferenceStore
        prefs.aichatKeyboardBackground.imageFile.set(name)
        deleteAllExcept(context, name)
        Outcome.SET
    }

    /** Removes the background: the keyboard goes back to the plain theme color. */
    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val prefs by FlorisPreferenceStore
        prefs.aichatKeyboardBackground.imageFile.set("")
        deleteAllExcept(context, keep = null)
    }

    private fun importOrThrow(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Bounds-only decodes return null by design; yield Unit so `?:` fires only if the stream cannot be opened.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit } ?: return null
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = StickerImageMath.sampleSize(width, height, MIN_SIDE_PX)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        var output: Bitmap? = null
        try {
            val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val scaled = scaleAndRotate(decoded, rotation)
            output = scaled
            val dir = dir(context).apply { mkdirs() }
            val name = "bg-${UUID.randomUUID()}.jpg"
            writeAtomically(File(dir, name)) { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            return name
        } finally {
            if (output != null && output !== decoded) output.recycle()
            decoded.recycle()
        }
    }

    private fun scaleAndRotate(source: Bitmap, rotationDegrees: Int): Bitmap {
        val (width, height) = StickerImageMath.fitInside(source.width, source.height, MAX_SIDE_PX)
        val matrix = Matrix().apply {
            postScale(width.toFloat() / source.width, height.toFloat() / source.height)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun deleteAllExcept(context: Context, keep: String?) {
        dir(context).listFiles()?.forEach { file ->
            if (file.name != keep && !file.delete()) Log.w(TAG, "Cannot delete old background ${file.name}")
        }
    }
}
