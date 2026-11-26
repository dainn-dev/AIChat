package com.aichat.keyboard.sticker.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.graphics.vector.ImageVector
import com.aichat.keyboard.sticker.StickerImportActivity
import com.aichat.keyboard.sticker.TextStickerMakerActivity
import com.aichat.keyboard.sticker.creator.StickerCreatorActivity
import dev.patrickgold.florisboard.R

/** One way to add a sticker to "Của tôi", offered when the user taps the add tile. */
internal class StickerAddOption(
    /** Stable key of the option row. */
    val key: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    /** Opens the flow from the keyboard (a service context: activities need a new task). */
    val launch: (Context) -> Unit,
)

/** The add options in menu order. To add a way of making stickers, append an entry here. */
internal val StickerAddOptions: List<StickerAddOption> = listOf(
    // iOS-like: lift the subject out of a photo (on-device ML Kit), then pick an effect.
    StickerAddOption(
        key = "cutout",
        label = R.string.aichat__sticker_creator__add_option,
        icon = Icons.Outlined.ContentCut,
        launch = { context -> StickerCreatorActivity.launch(context) },
    ),
    StickerAddOption(
        key = "gallery",
        label = R.string.aichat__stickers__add_from_gallery,
        icon = Icons.Outlined.PhotoLibrary,
        launch = StickerImportActivity::launch,
    ),
    StickerAddOption(
        key = "text",
        label = R.string.aichat__stickers__add_text,
        icon = Icons.Outlined.TextFields,
        launch = TextStickerMakerActivity::launch,
    ),
)
