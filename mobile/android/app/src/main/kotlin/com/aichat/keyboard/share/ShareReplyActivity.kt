/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aichat.keyboard.core.network.AiChat
import com.aichat.keyboard.core.network.model.Tone
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import org.florisboard.lib.compose.ProvideLocalizedResources

/**
 * Share target for chat screenshots ("Gợi ý trả lời AIChat" in the share sheet): on-device OCR ([ScreenshotOcr]),
 * a preview of the detected conversation, tone chips and reply suggestions to copy.
 *
 * Only the parsed text goes to `POST /v1/keyboard/reply`; the image never leaves the phone and is not stored.
 * The activity is exported for its `ACTION_SEND image/<any>` filter, so it accepts nothing but a `content:` image.
 */
class ShareReplyActivity : ComponentActivity() {
    private val viewModel: ShareReplyViewModel by viewModels {
        val appContext = applicationContext
        val uri = sharedImageUri(intent)
        viewModelFactory {
            initializer {
                ShareReplyViewModel(
                    readChat = { uri?.let { ScreenshotOcr.read(appContext, it) } },
                    sendReply = { request -> AiChat.client(appContext).reply(request) },
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The chat must not show up in the recents thumbnail or in screen captures of this screen.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        viewModel.start()

        val actions = object : ShareReplyActions {
            override fun flipSender(index: Int) = viewModel.flipSender(index)
            override fun requestReplies(tone: Tone) = viewModel.requestReplies(tone)
            override fun retry() = viewModel.retry()
            override fun signIn() = openSettings()
            override fun copy(text: String) = copyToClipboard(text)
            override fun close() = finish()
        }

        setContent {
            ProvideLocalizedResources(this, appName = R.string.aichat_app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        val state by viewModel.state.collectAsState()
                        // Back from signing in: try again (a signed-out request fails fast without network).
                        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                            val reply = viewModel.state.value.reply
                            if (reply is ShareReplyViewModel.ReplyState.Failed &&
                                reply.failure == ShareFailure.NOT_SIGNED_IN
                            ) {
                                viewModel.retry()
                            }
                        }
                        ShareReplyScreen(state, actions)
                    }
                }
            }
        }
    }

    /** The AIChat account entry is at the top of the settings home screen. */
    private fun openSettings() {
        startActivity(Intent(this, FlorisAppActivity::class.java))
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.aichat__share__title), text))
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.aichat__share__copied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * A `content:` image from `ACTION_SEND`, or null for anything else (explicit intents included). URIs served by
     * this app's own providers are refused (see [SharedImageUriGuard]).
     */
    private fun sharedImageUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("image/") != true) return null
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null
        val accepted = SharedImageUriGuard.accepts(
            scheme = uri.scheme,
            authority = uri.encodedAuthority,
            ownPackage = packageName,
            resolveOwner = { authority -> providerOwner(authority) },
        )
        // Diagnostics: scheme/authority only, never the path or the image.
        Log.i(TAG, "Shared image: scheme=${uri.scheme} authority=${uri.encodedAuthority} accepted=$accepted")
        return if (accepted) uri else null
    }

    private fun providerOwner(authority: String): String? = try {
        packageManager.resolveContentProvider(authority, 0)?.packageName
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        const val TAG = "ShareReply"
    }
}
