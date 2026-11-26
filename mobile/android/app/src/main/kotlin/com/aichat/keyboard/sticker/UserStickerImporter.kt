package com.aichat.keyboard.sticker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/**
 * Turns an image the user picked into a sticker: decoded with sampling, rotated per EXIF, scaled to fit
 * [StickerRenderer.SIZE_PX] and stored as PNG (keeps transparency) in [UserStickerFiles].
 * Blocking; call it off the main thread.
 */
object UserStickerImporter {
    enum class Outcome { ADDED, FULL, INVALID }

    private const val TAG = "UserStickerImporter"

    /** Refuse decoding anything larger (e.g. a 200 MP panorama), so a single image cannot exhaust memory. */
    private const val MAX_SOURCE_PIXELS = 50_000_000L
    private const val PNG_QUALITY = 100

    fun import(context: Context, uri: Uri, files: UserStickerFiles): Outcome {
        return try {
            importOrThrow(context, uri, files)
        } catch (e: IOException) {
            Log.w(TAG, "Cannot import sticker image", e)
            Outcome.INVALID
        } catch (e: SecurityException) {
            Log.w(TAG, "No read access to the picked image", e)
            Outcome.INVALID
        } catch (e: OutOfMemoryError) {
            // The decode is sampled, but a low-RAM device can still run out: skip this image, keep the others.
            Log.w(TAG, "Not enough memory to import the picked image", e)
            Outcome.INVALID
        }
    }

    private fun importOrThrow(context: Context, uri: Uri, files: UserStickerFiles): Outcome {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return Outcome.INVALID
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return Outcome.INVALID

        val target = files.newStickerFile() ?: return Outcome.FULL

        val options = BitmapFactory.Options().apply {
            inSampleSize = StickerImageMath.sampleSize(width, height, StickerRenderer.SIZE_PX)
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return Outcome.INVALID
        var sticker: Bitmap? = null
        try {
            val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            val scaled = scaleAndRotate(decoded, rotation)
            sticker = scaled
            writeAtomically(target) { scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        } finally {
            if (sticker != null && sticker !== decoded) sticker.recycle()
            decoded.recycle()
        }
        return Outcome.ADDED
    }

    private fun scaleAndRotate(source: Bitmap, rotationDegrees: Int): Bitmap {
        val (width, height) = StickerImageMath.fitInside(source.width, source.height, StickerRenderer.SIZE_PX)
        val matrix = Matrix().apply {
            postScale(width.toFloat() / source.width, height.toFloat() / source.height)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
