/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import kotlinx.serialization.Serializable
import org.florisboard.lib.compose.stringRes
import dev.patrickgold.florisboard.app.LocalNavController

object UpdateRoutes {
    @Serializable
    object Screen
}

/** Registers the update screen. Called from `Routes.AppNavHost`. */
fun NavGraphBuilder.aichatUpdateGraph() {
    composable<UpdateRoutes.Screen> { UpdateScreen() }
}

/** "Cập nhật ứng dụng" entry in the settings home screen, with the installed version or the available one. */
@Composable
fun AiChatUpdatePreference() {
    val navController = LocalNavController.current
    val state by AppUpdater.state.collectAsState()
    val newer = when (val s = state) {
        is UpdateState.Available -> s.release
        is UpdateState.Downloading -> s.release
        is UpdateState.ReadyToInstall -> s.release
        else -> null
    }
    Preference(
        icon = Icons.Outlined.SystemUpdate,
        title = stringRes(R.string.aichat__update__title),
        summary = if (newer != null) {
            stringRes(R.string.aichat__update__summary_available, "version" to newer.versionName)
        } else {
            stringRes(R.string.aichat__update__summary_current, "version" to BuildConfig.VERSION_NAME)
        },
        onClick = { navController.navigate(UpdateRoutes.Screen) },
    )
}

@Composable
fun UpdateScreen() = FlorisScreen {
    title = stringRes(R.string.aichat__update__title)
    previewFieldVisible = false

    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()

    content {
        LaunchedEffect(Unit) {
            if (AppUpdater.isSupported && state !is UpdateState.Downloading && state !is UpdateState.Installing) {
                AppUpdater.checkNow(context)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringRes(
                    R.string.aichat__update__installed,
                    "version" to BuildConfig.VERSION_NAME,
                    "build" to BuildConfig.VERSION_CODE,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!AppUpdater.isSupported) {
                Text(stringRes(R.string.aichat__update__unsupported), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(statusText(state), style = MaterialTheme.typography.bodyMedium)
            if (state is UpdateState.Downloading) {
                LinearProgressIndicator(
                    progress = { (state as UpdateState.Downloading).progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when (val s = state) {
                is UpdateState.Available, is UpdateState.ReadyToInstall -> {
                    val release = (s as? UpdateState.Available)?.release ?: (s as UpdateState.ReadyToInstall).release
                    Button(onClick = { startUpdate(context) }) {
                        Text(stringRes(R.string.aichat__update__install, "version" to release.versionName))
                    }
                }
                is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Failed -> {
                    Button(onClick = { AppUpdater.checkNow(context) }) {
                        Text(stringRes(R.string.aichat__update__check))
                    }
                }
                else -> Unit
            }
        }

        if (AppUpdater.isSupported) {
            SwitchPreference(
                prefs.aichatUpdates.autoUpdate,
                title = stringRes(R.string.aichat__update__auto),
                summary = stringRes(R.string.aichat__update__auto_summary),
            )
        }
    }
}

@Composable
private fun statusText(state: UpdateState): String = when (state) {
    UpdateState.Idle, UpdateState.Checking -> stringRes(R.string.aichat__update__checking)
    UpdateState.UpToDate -> stringRes(R.string.aichat__update__up_to_date)
    is UpdateState.Available -> stringRes(R.string.aichat__update__available, "version" to state.release.versionName)
    is UpdateState.Downloading -> stringRes(R.string.aichat__update__downloading, "version" to state.release.versionName)
    is UpdateState.ReadyToInstall -> stringRes(R.string.aichat__update__ready, "version" to state.release.versionName)
    is UpdateState.Installing -> stringRes(R.string.aichat__update__installing, "version" to state.release.versionName)
    is UpdateState.Failed -> stringRes(
        when (state.reason) {
            UpdateState.Reason.NETWORK -> R.string.aichat__update__error_network
            UpdateState.Reason.VERIFY -> R.string.aichat__update__error_verify
            UpdateState.Reason.INSTALL -> R.string.aichat__update__error_install
        },
    )
}

/** Installing needs "Install unknown apps" for this app; ask for it first, then update. */
private fun startUpdate(context: Context) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // Fall through: the installer asks itself.
        }
    }
    AppUpdater.updateNow(context)
}
