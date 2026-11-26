package com.aichat.keyboard.theme.background

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.jetpref.datastore.model.observeAsState
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.coroutines.launch
import org.florisboard.lib.compose.stringRes

/**
 * "Hình nền bàn phím" on the theme settings screen: pick a photo from the gallery as the keyboard background,
 * or remove it. The photo picker needs no storage permission. The change shows in the preview field right away,
 * since `ThemeManager` observes the pref.
 */
@Composable
fun KeyboardBackgroundPreferences() {
    val context = LocalContext.current
    val prefs by FlorisPreferenceStore
    val imageFile by prefs.aichatKeyboardBackground.imageFile.observeAsState()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val errorText = stringRes(R.string.aichat__keyboard_background__error)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val outcome = KeyboardBackgroundStore.setFromUri(context.applicationContext, uri)
            importing = false
            if (outcome == KeyboardBackgroundStore.Outcome.INVALID) {
                Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Preference(
        icon = Icons.Default.Wallpaper,
        title = stringRes(R.string.aichat__keyboard_background__title),
        summary = when {
            importing -> stringRes(R.string.aichat__keyboard_background__importing)
            imageFile.isEmpty() -> stringRes(R.string.aichat__keyboard_background__summary_none)
            else -> stringRes(R.string.aichat__keyboard_background__summary_set)
        },
        onClick = {
            if (!importing) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
    )
    if (imageFile.isNotEmpty() && !importing) {
        Preference(
            icon = Icons.Default.HideImage,
            title = stringRes(R.string.aichat__keyboard_background__remove),
            onClick = {
                scope.launch { KeyboardBackgroundStore.clear(context.applicationContext) }
            },
        )
    }
}
