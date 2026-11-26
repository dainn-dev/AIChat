package com.aichat.keyboard.sticker

import android.content.Context
import android.util.Log
import dev.patrickgold.florisboard.R
import kotlinx.coroutines.CancellationException
import org.florisboard.lib.android.showShortToast

/**
 * The one entry point every sticker UI uses to send a sticker (palette, typing suggestions, AI reply suggestions):
 * sends through [StickerSender], never throws (except cancellation) and tells the user when it did not work.
 * Must be called on the main thread.
 */
object StickerSendFlow {
    private const val TAG = "StickerSendFlow"

    suspend fun sendAndReport(context: Context, sticker: Sticker): StickerSender.Result {
        // An uncaught exception here would take down the whole keyboard process.
        val result = try {
            StickerSender.send(context, sticker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sending sticker ${sticker.id} failed", e)
            StickerSender.Result.FAILED
        }
        report(context, result)
        return result
    }

    private suspend fun report(context: Context, result: StickerSender.Result) {
        when (result) {
            StickerSender.Result.UNSUPPORTED -> context.showShortToast(R.string.aichat__stickers__unsupported)
            StickerSender.Result.FAILED -> context.showShortToast(R.string.aichat__stickers__send_failed)
            StickerSender.Result.SENT,
            StickerSender.Result.SENT_AS_TEXT,
            StickerSender.Result.TARGET_CHANGED -> Unit
        }
    }
}
