package com.aichat.keyboard.sticker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.aichat.keyboard.sticker.StickerRenderer
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.aichat.keyboard.sticker.Sticker
import com.aichat.keyboard.sticker.StickerImageMath
import com.aichat.keyboard.sticker.StickerRepository
import com.aichat.keyboard.sticker.StickerSource
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small decoded previews of image stickers for the palette grid, kept in a memory LRU cache. */
internal object StickerThumbnails {
    private const val TAG = "StickerThumbnails"
    private const val THUMBNAIL_PX = 192
    /** The keyboard process is memory-sensitive: bound the cache by bytes, not entries. */
    private const val CACHE_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    fun cached(sticker: Sticker): ImageBitmap? = cache.get(sticker.id)

    suspend fun load(context: Context, sticker: Sticker): ImageBitmap? = withContext(Dispatchers.IO) {
        val open: () -> InputStream? = when (val source = sticker.source) {
            is StickerSource.Asset -> { -> context.assets.open(source.path) }
            is StickerSource.UserFile -> { ->
                StickerRepository.get(context).userFiles.fileFor(source.id)?.inputStream()
            }
            // Drawn by the same renderer as the sent image, so the preview matches it and the text is fitted.
            is StickerSource.Text, is StickerSource.Emoji -> return@withContext try {
                rendered(source)?.also { cache.put(sticker.id, it) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Cannot render thumbnail of ${sticker.id}", e)
                null
            }
        }
        try {
            decode(open)?.also { cache.put(sticker.id, it) }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot decode thumbnail of ${sticker.id}", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot decode thumbnail of ${sticker.id}", e)
            null
        }
    }

    private fun rendered(source: StickerSource): ImageBitmap? {
        val full = StickerRenderer.render(source) ?: return null
        val small = Bitmap.createScaledBitmap(full, THUMBNAIL_PX, THUMBNAIL_PX, true)
        if (small !== full) full.recycle()
        return small.asImageBitmap()
    }

    private fun decode(open: () -> InputStream?): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = StickerImageMath.sampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_PX)
        }
        return open()?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    }
}
