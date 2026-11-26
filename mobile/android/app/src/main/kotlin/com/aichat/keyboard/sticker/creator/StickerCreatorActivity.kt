/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aichat.keyboard.share.SharedImageUriGuard
import com.aichat.keyboard.sticker.UserStickerFiles
import com.aichat.keyboard.sticker.creator.StickerCreatorViewModel.ErrorKind
import com.aichat.keyboard.sticker.creator.StickerCreatorViewModel.State
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.ProvideLocalizedResources

/**
 * "Create sticker from photo" (iOS-style): the subject is cut out on the device (ML Kit subject segmentation), the
 * user picks subjects and an effect, and the sticker lands in "My stickers". The photo never leaves the phone.
 *
 * Opened from the keyboard ([launch], with or without a photo; without one it shows the system photo picker, which
 * needs no storage permission) or from the share sheet ("Tạo sticker", `ACTION_SEND image/<any>`). Exported only for
 * that share filter: any URI it gets is checked with [SharedImageUriGuard], so this app's own providers are refused.
 */
class StickerCreatorActivity : ComponentActivity() {
    private val viewModel: StickerCreatorViewModel by viewModels {
        val appContext = applicationContext
        viewModelFactory {
            initializer {
                StickerCreatorViewModel(
                    segmenter = SubjectSegmenters.create(appContext),
                    store = UserStickerCreatorStore(appContext),
                )
            }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            viewModel.photoPickerCancelled()
            // Nothing picked and nothing to show: back to where the user came from.
            if (viewModel.state.value == State.Ready) finish()
        } else {
            viewModel.open(loader(uri))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        viewModel.start(incomingImageUri(intent)?.let { loader(it) })

        val actions = object : StickerCreatorActions {
            override fun pickPhoto() = openPicker()
            override fun pickAnother() = viewModel.pickAnother()
            override fun tap(x: Int, y: Int) = viewModel.tap(x, y)
            override fun toggle(index: Int) = viewModel.toggle(index)
            override fun selectAll() = viewModel.selectAll()
            override fun confirmSelection() = viewModel.confirmSelection()
            override fun useWholePhoto() = viewModel.useWholePhoto()
            override fun selectEffect(effect: StickerEffect) = viewModel.selectEffect(effect)
            override fun save() = viewModel.save()
            override fun retry() = viewModel.retry()
            override fun back() {
                if (!viewModel.back()) finish()
            }
            override fun close() = finish()
        }

        setContent {
            ProvideLocalizedResources(this, appName = R.string.aichat_app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        val state by viewModel.state.collectAsState()
                        BackHandler(enabled = state is State.Preview) { actions.back() }
                        LaunchedEffect(state) { onStateChanged(state) }
                        StickerCreatorScreen(state, actions)
                    }
                }
            }
        }
    }

    private suspend fun onStateChanged(state: State) {
        when {
            state == State.Ready -> if (viewModel.claimPhotoPicker()) launchPicker()
            state == State.Saved -> {
                applicationContext.showShortToast(R.string.aichat__sticker_creator__saved)
                finish()
            }
            state is State.Error && state.kind == ErrorKind.STORE_FULL -> showShortToast(
                R.string.aichat__stickers__import_full,
                "max" to UserStickerFiles.MAX_USER_STICKERS,
            )
        }
    }

    /** The "choose photo" button (e.g. after the picker was dismissed in another way). */
    private fun openPicker() {
        if (viewModel.claimPhotoPicker()) launchPicker()
    }

    private fun launchPicker() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Decodes on the IO pool; only the application context is captured, so rotation leaks nothing. */
    private fun loader(uri: Uri): StickerImageLoader {
        val appContext = applicationContext
        return { withContext(Dispatchers.IO) { StickerBitmaps.decodeUpright(appContext, uri) } }
    }

    /**
     * The photo handed to this activity: `EXTRA_STREAM` of an `ACTION_SEND image/<any>`, or [EXTRA_IMAGE_URI] from
     * [launch]. Null for anything else, and for URIs this app's own providers serve (see [SharedImageUriGuard]).
     */
    private fun incomingImageUri(intent: Intent?): Uri? {
        intent ?: return null
        val uri = if (intent.action == Intent.ACTION_SEND) {
            if (intent.type?.startsWith("image/") != true) return null
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            IntentCompat.getParcelableExtra(intent, EXTRA_IMAGE_URI, Uri::class.java)
        } ?: return null
        val accepted = SharedImageUriGuard.accepts(
            scheme = uri.scheme,
            authority = uri.encodedAuthority,
            ownPackage = packageName,
            resolveOwner = { authority -> providerOwner(authority) },
        )
        // Diagnostics: scheme/authority only, never the path or the image.
        Log.i(TAG, "Incoming image: scheme=${uri.scheme} authority=${uri.encodedAuthority} accepted=$accepted")
        return if (accepted) uri else null
    }

    private fun providerOwner(authority: String): String? = try {
        packageManager.resolveContentProvider(authority, 0)?.packageName
    } catch (_: RuntimeException) {
        null
    }

    companion object {
        private const val TAG = "StickerCreator"
        private const val EXTRA_IMAGE_URI = "com.aichat.keyboard.sticker.creator.extra.IMAGE_URI"

        /**
         * Opens the creator from the keyboard (a service context, hence the new task). Without [imageUri] it starts
         * with the photo picker. [imageUri] must be readable by this app and not served by its own providers.
         */
        fun launch(context: Context, imageUri: Uri? = null) {
            val intent = Intent(context, StickerCreatorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (imageUri != null) intent.putExtra(EXTRA_IMAGE_URI, imageUri)
            context.startActivity(intent)
        }
    }
}
