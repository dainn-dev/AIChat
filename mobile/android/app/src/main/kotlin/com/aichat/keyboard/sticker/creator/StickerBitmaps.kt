/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.aichat.keyboard.sticker.StickerImageMath
import com.aichat.keyboard.sticker.StickerRepository
import com.aichat.keyboard.sticker.UserStickerFiles
import com.aichat.keyboard.sticker.writeAtomically
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Android edges of the sticker creator: photo decoding, [StickerPixels] ↔ [Bitmap], and PNG storage. */
object StickerBitmaps {
    private const val TAG = "StickerBitmaps"

    /** Refuse decoding anything larger (e.g. a 200 MP panorama), like `UserStickerImporter`. */
    private const val MAX_SOURCE_PIXELS = 50_000_000L
    private const val PNG_QUALITY = 100

    /**
     * Decodes the image at [uri] upright (EXIF rotation applied), with its longer side at most [maxLongSide].
     * Null when it cannot be read, is too large or memory runs out. Blocking; call it off the main thread.
     */
    fun decodeUpright(context: Context, uri: Uri, maxLongSide: Int = CutoutSpec.MAX_INPUT_PX): StickerPixels? {
        return try {
            decodeOrNull(context, uri, maxLongSide)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read the photo", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No read access to the photo", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Cannot decode the photo", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Not enough memory to open the photo", e)
            null
        }
    }

    private fun decodeOrNull(context: Context, uri: Uri, maxLongSide: Int): StickerPixels? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = StickerCreatorMath.sampleSizeForLongSide(width, height, maxLongSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        var upright: Bitmap? = null
        try {
            val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            upright = scaleAndRotate(decoded, rotation, maxLongSide)
            return fromBitmap(upright)
        } finally {
            if (upright != null && upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    private fun scaleAndRotate(source: Bitmap, rotationDegrees: Int, maxLongSide: Int): Bitmap {
        val (width, height) = StickerImageMath.fitInside(source.width, source.height, maxLongSide)
        val matrix = Matrix().apply {
            postScale(width.toFloat() / source.width, height.toFloat() / source.height)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Pixels of [bitmap] (any config; read as non-premultiplied ARGB). */
    fun fromBitmap(bitmap: Bitmap): StickerPixels {
        val argb = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return StickerPixels(bitmap.width, bitmap.height, argb)
    }

    /** A new ARGB_8888 bitmap of [pixels]. */
    fun toBitmap(pixels: StickerPixels): Bitmap =
        Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888)

    /**
     * Stores [sticker] as a new PNG in [files] (written atomically). False when the store is full.
     * Blocking; throws [IOException] when the file cannot be written.
     */
    fun savePng(sticker: StickerPixels, files: UserStickerFiles): Boolean {
        val target = files.newStickerFile() ?: return false
        val bitmap = toBitmap(sticker)
        try {
            writeAtomically(target) { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) throw IOException("PNG encode failed")
            }
        } finally {
            bitmap.recycle()
        }
        return true
    }
}

/** Where the sticker creator keeps what it makes. Behind an interface so the view model is tested without files. */
interface StickerCreatorStore {
    enum class SaveOutcome { ADDED, FULL }

    suspend fun isFull(): Boolean

    /** Stores [sticker]; throws on I/O errors. */
    suspend fun save(sticker: StickerPixels): SaveOutcome

    /** Lets the keyboard's sticker palette pick up the new sticker. */
    suspend fun refresh()
}

/** The user's own stickers ("My stickers" in the keyboard), through [StickerRepository]. */
class UserStickerCreatorStore(context: Context) : StickerCreatorStore {
    private val repository = StickerRepository.get(context)

    override suspend fun isFull(): Boolean = withContext(Dispatchers.IO) { repository.userFiles.isFull() }

    override suspend fun save(sticker: StickerPixels): StickerCreatorStore.SaveOutcome = withContext(Dispatchers.IO) {
        if (StickerBitmaps.savePng(sticker, repository.userFiles)) {
            StickerCreatorStore.SaveOutcome.ADDED
        } else {
            StickerCreatorStore.SaveOutcome.FULL
        }
    }

    override suspend fun refresh() = repository.refresh()
}
