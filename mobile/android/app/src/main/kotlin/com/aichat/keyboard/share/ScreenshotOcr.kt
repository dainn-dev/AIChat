/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR of a shared chat screenshot with ML Kit's bundled Latin model (Vietnamese is Latin script).
 *
 * Privacy: the image is decoded straight from the content URI into memory, never copied to disk, and the bitmap is
 * recycled as soon as recognition ends. Nothing about the image or its text is logged.
 */
object ScreenshotOcr {
    private const val TAG = "ShareReply"

    /** Long screenshots are scaled down to keep the bitmap small; text stays readable at this width. */
    private const val MAX_WIDTH = 1440
    private const val MAX_PIXELS = 1440 * 8000

    /** Larger sources are refused before decoding (decompression bombs, absurd panoramas). */
    private const val MAX_SOURCE_PIXELS = 100_000_000L

    /** Decode + recognition budget; past it the image counts as unreadable. */
    private const val READ_TIMEOUT_MS = 20_000L

    /** Whether an image of these bounds may be decoded at all. */
    internal fun isDecodable(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width.toLong() * height <= MAX_SOURCE_PIXELS

    /** Reads [uri] and returns the parsed chat, or null when it is not a readable image or reading took too long. */
    suspend fun read(context: Context, uri: Uri): ParsedChat? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(READ_TIMEOUT_MS) { decodeAndRecognize(context, uri) }
    }

    private suspend fun decodeAndRecognize(context: Context, uri: Uri): ParsedChat? {
        val bitmap = runInterruptible { decode(context, uri) }
        if (bitmap == null) {
            Log.w(TAG, "Screenshot: decode failed")
            return null
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var cancelled = false
        return try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val lines = text.toOcrLines()
            ChatScreenshotParser.parse(lines, bitmap.width, bitmap.height).also {
                // Counts and sizes only; the text itself is never logged.
                Log.i(TAG, "Screenshot: ${bitmap.width}x${bitmap.height}, ocrLines=${lines.size}, messages=${it.messages.size}")
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // ML Kit reports failures as MlKitException; any failure means "could not read this image".
            Log.w(TAG, "Screenshot: recognition failed (${e.javaClass.simpleName})")
            null
        } finally {
            // After a cancellation ML Kit may still be reading the bitmap: leave both to the garbage collector.
            if (!cancelled) {
                recognizer.close()
                bitmap.recycle()
            }
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (!isDecodable(bounds.outWidth, bounds.outHeight)) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (e: IOException) {
        Log.w(TAG, "Screenshot: image I/O failed (${e.javaClass.simpleName})")
        null
    } catch (e: SecurityException) {
        Log.w(TAG, "Screenshot: image permission denied")
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_WIDTH || (width / sample).toLong() * (height / sample) > MAX_PIXELS) {
            sample *= 2
        }
        return sample
    }

    private fun Text.toOcrLines(): List<OcrLine> = textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
