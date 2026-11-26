/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter as MlKitClient

/**
 * [SubjectSegmenter] backed by ML Kit Subject Segmentation. The library is unbundled: the model ships with Google Play
 * services and is fetched on first use, so availability is checked through `ModuleInstall` rather than assumed.
 *
 * Privacy: the image only lives in memory for the duration of [segment] and nothing about it is logged.
 */
class MlKitSubjectSegmenter(context: Context) : SubjectSegmenter {
    private val appContext = context.applicationContext

    /** One client for availability checks, install and inference; it is also the `OptionalModuleApi`. */
    private val client: MlKitClient by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(
                    SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build(),
                )
                .build(),
        )
    }

    override suspend fun availability(): SegmenterAvailability = try {
        val services = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (services != ConnectionResult.SUCCESS) {
            SegmenterAvailability.Unsupported
        } else {
            val response = ModuleInstall.getClient(appContext).areModulesAvailable(client).await()
            if (response.areModulesAvailable()) SegmenterAvailability.Ready else SegmenterAvailability.NeedsDownload
        }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // ApiException (Play services too old for module install) and friends: the feature cannot work here.
        SegmenterAvailability.Unsupported
    }

    override suspend fun ensureReady(): Boolean {
        when (availability()) {
            SegmenterAvailability.Ready -> return true
            SegmenterAvailability.Unsupported -> return false
            SegmenterAvailability.NeedsDownload -> Unit
        }
        val installer = ModuleInstall.getClient(appContext)
        val finished = CompletableDeferred<Boolean>()
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_COMPLETED -> finished.complete(true)
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> finished.complete(false)
            }
        }
        return try {
            val request = ModuleInstallRequest.newBuilder().addApi(client).setListener(listener).build()
            // The task completes when the request is accepted; the download itself is reported to the listener.
            val response = installer.installModules(request).await()
            if (response.areModulesAlreadyInstalled()) {
                true
            } else {
                // A download paused for Wi-Fi may never finish: give up after a while and let the UI offer a retry.
                withTimeoutOrNull(INSTALL_TIMEOUT_MS) { finished.await() } == true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            false
        } finally {
            installer.unregisterListener(listener)
        }
    }

    override suspend fun segment(source: StickerPixels): SegmentationResult = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(source.argb, source.width, source.height, Bitmap.Config.ARGB_8888)
        var cancelled = false
        try {
            val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            SegmentationResult(
                imageWidth = source.width,
                imageHeight = source.height,
                subjects = result.subjects.mapNotNull { subject ->
                    detectedSubject(subject.startX, subject.startY, subject.width, subject.height, subject.confidenceMask)
                },
            )
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // MlKitException (model missing, bad input) and the like: the UI offers a retry.
            throw IOException("Subject segmentation failed (${e.javaClass.simpleName})", e)
        } finally {
            // After a cancellation ML Kit may still be reading the bitmap: leave it to the garbage collector.
            if (!cancelled) bitmap.recycle()
        }
    }

    override fun close() {
        client.close()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    internal companion object {
        /** Upper bound for the one-time model download (a few MB). */
        private const val INSTALL_TIMEOUT_MS = 120_000L

        /**
         * Copies one ML Kit subject into a [DetectedSubject]. ML Kit reuses the mask buffer between calls, so it is
         * copied out right away. Returns null for an empty box or a mask of the wrong size (skipped, not fatal).
         */
        fun detectedSubject(left: Int, top: Int, width: Int, height: Int, mask: FloatBuffer?): DetectedSubject? {
            if (width <= 0 || height <= 0 || mask == null) return null
            val buffer = mask.duplicate()
            buffer.rewind()
            if (buffer.remaining() != width * height) return null
            val confidence = FloatArray(width * height)
            buffer.get(confidence)
            return DetectedSubject(left, top, width, height, confidence)
        }
    }
}
