/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
    /** Long screenshots are scaled down to keep the bitmap small; text stays readable at this width. */
    private const val MAX_WIDTH = 1440
    private const val MAX_PIXELS = 1440 * 8000

    /** Reads [uri] and returns the parsed chat, or null when it is not a readable image. */
    suspend fun read(context: Context, uri: Uri): ParsedChat? = withContext(Dispatchers.IO) {
        val bitmap = decode(context, uri) ?: return@withContext null
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var cancelled = false
        try {
            val text = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            ChatScreenshotParser.parse(text.toOcrLines(), bitmap.width, bitmap.height)
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // ML Kit reports failures as MlKitException; any failure means "could not read this image".
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
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
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
