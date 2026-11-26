/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import kotlinx.serialization.Serializable
import org.florisboard.lib.compose.stringRes

/**
 * Prominent disclosure and consent for the chat reader (Google Play's accessibility policy): what is read (including
 * end-to-end encrypted chats), when, where it goes, the clipboard fallback, and how to turn it off, before the user
 * is sent to the system accessibility settings. The user can agree or decline ("No thanks" just goes back).
 *
 * No `ui://florisboard/...` deep link, like the other AIChat routes.
 */
object ChatCaptureRoutes {
    @Serializable
    object Consent
}

/** Registers the consent screen. Called from `Routes.AppNavHost`. */
fun NavGraphBuilder.aichatCaptureGraph() {
    composable<ChatCaptureRoutes.Consent> { ChatCaptureConsentScreen() }
}

/** "Đọc tin nhắn để gợi ý trả lời" entry in the settings home screen, with the current on/off state. */
@Composable
fun AiChatCapturePreference() {
    val navController = LocalNavController.current
    val enabled = rememberCaptureEnabled()
    Preference(
        icon = Icons.AutoMirrored.Outlined.Chat,
        title = stringRes(R.string.aichat__capture__title),
        summary = stringRes(
            if (enabled) R.string.aichat__capture__summary_on else R.string.aichat__capture__summary_off,
        ),
        onClick = { navController.navigate(ChatCaptureRoutes.Consent) },
    )
}

@Composable
fun ChatCaptureConsentScreen() = FlorisScreen {
    title = stringRes(R.string.aichat__capture__title)
    previewFieldVisible = false

    val context = LocalContext.current
    val navController = LocalNavController.current
    val enabled = rememberCaptureEnabled()

    content {
        Paragraph(R.string.aichat__capture__intro)
        Section(R.string.aichat__capture__what_heading, R.string.aichat__capture__what_body)
        Section(R.string.aichat__capture__when_heading, R.string.aichat__capture__when_body)
        Section(R.string.aichat__capture__where_heading, R.string.aichat__capture__where_body)
        Section(R.string.aichat__capture__clipboard_heading, R.string.aichat__capture__clipboard_body)
        Section(R.string.aichat__capture__off_heading, R.string.aichat__capture__off_body)

        Text(
            text = stringRes(if (enabled) R.string.aichat__capture__status_on else R.string.aichat__capture__status_off),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (enabled) {
                OutlinedButton(onClick = { openAccessibilitySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.aichat__capture__manage_button))
                }
            } else {
                Paragraph(R.string.aichat__capture__how_body, horizontalPadding = 0)
                Button(onClick = { openAccessibilitySettings(context) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.aichat__capture__agree_button))
                }
                // Declining just goes back; nothing is turned on.
                TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringRes(R.string.aichat__capture__decline_button))
                }
            }
        }
        if (!enabled && mayHitRestrictedSettings(context)) {
            Section(R.string.aichat__capture__restricted_heading, R.string.aichat__capture__restricted_body)
            OutlinedButton(
                onClick = { openAppInfo(context) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringRes(R.string.aichat__capture__restricted_button))
            }
        }
    }
}

/**
 * Android 13+ greys out the accessibility switch ("Restricted setting") for apps sideloaded from a browser or file
 * manager (e.g. the APK from GitHub releases) until the user taps "Allow restricted settings" in App info. There is
 * no public API to read that state, so show the steps for every non-Play install on 13+ while the service is off.
 */
private fun mayHitRestrictedSettings(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val installer = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()
    return installer != PLAY_STORE_PACKAGE
}

private const val PLAY_STORE_PACKAGE = "com.android.vending"

private fun openAppInfo(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Whether the service is on; re-read whenever the screen resumes (the user may come back from Settings). */
@Composable
private fun rememberCaptureEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isCaptureEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = isCaptureEnabled(context) }
    return enabled
}

private fun isCaptureEnabled(context: Context): Boolean =
    runCatching { ChatCaptureService.isEnabled(context) }.getOrDefault(false)

@Composable
private fun Section(@StringRes heading: Int, @StringRes body: Int) {
    Text(
        text = stringRes(heading),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Paragraph(body)
}

@Composable
private fun Paragraph(@StringRes text: Int, horizontalPadding: Int = 16) {
    Text(
        text = stringRes(text),
        modifier = Modifier.padding(horizontal = horizontalPadding.dp, vertical = 6.dp),
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
