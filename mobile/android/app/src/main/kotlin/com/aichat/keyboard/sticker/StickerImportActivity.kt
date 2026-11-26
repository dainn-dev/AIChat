package com.aichat.keyboard.sticker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.patrickgold.florisboard.R
import org.florisboard.lib.android.showShortToast

/**
 * Invisible activity the keyboard opens from the "+" sticker tile: it shows the system photo picker (no storage
 * permission needed), hands the chosen images to [StickerRepository.importInBackground] and closes. Not exported.
 */
class StickerImportActivity : ComponentActivity() {
    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK),
    ) { uris -> onPicked(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun onPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            // The import outlives this activity (only the app context is captured): it closes now and the keyboard
            // comes back right away; the palette updates when the import is done.
            val appContext = applicationContext
            StickerRepository.get(appContext).importInBackground(uris) { outcomes ->
                appContext.showStickerImportSummary(outcomes)
            }
        }
        finish()
    }

    companion object {
        private const val MAX_PICK = 20

        /** Opens the picker from the keyboard (a service context, hence the new task). */
        fun launch(context: Context) {
            val intent = Intent(context, StickerImportActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(intent)
        }
    }
}

private suspend fun Context.showStickerImportSummary(outcomes: List<UserStickerImporter.Outcome>) {
    val added = outcomes.count { it == UserStickerImporter.Outcome.ADDED }
    val invalid = outcomes.count { it == UserStickerImporter.Outcome.INVALID }
    when {
        outcomes.any { it == UserStickerImporter.Outcome.FULL } -> showShortToast(
            R.string.aichat__stickers__import_full,
            "max" to UserStickerFiles.MAX_USER_STICKERS,
        )
        invalid > 0 -> showShortToast(R.string.aichat__stickers__import_invalid, "count" to invalid)
        else -> showShortToast(R.string.aichat__stickers__import_added, "count" to added)
    }
}
