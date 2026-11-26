/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Status of the update install session started by [AppUpdater]. Not exported: only the system's installer calls it
 * back, through the app's own (mutable, explicit) pending intent.
 *
 * When Android wants the user to confirm: started from the update screen ([AppUpdater.EXTRA_INTERACTIVE]), the
 * confirmation opens at once (the app is in front); started in the background, a notification carries it, since
 * a background app may not open screens.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val versionName = intent.getStringExtra(AppUpdater.EXTRA_VERSION_NAME).orEmpty()
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent) ?: return AppUpdater.onInstallFailed(aborted = false)
                if (intent.getBooleanExtra(AppUpdater.EXTRA_INTERACTIVE, false)) {
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        return
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "No installer confirmation screen")
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Cannot open the installer confirmation")
                    }
                }
                UpdateNotifications.showConfirm(context, versionName, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The process is being replaced.
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdater.onInstallFailed(aborted = true)
            else -> {
                Log.w(TAG, "Update install failed: status=$status")
                AppUpdater.onInstallFailed(aborted = false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val TAG = "UpdateInstall"
        const val ACTION_STATUS = "com.aichat.keyboard.update.INSTALL_STATUS"
    }
}

/** After this app replaced itself: says which version is now installed, and only for updates it installed. */
class UpdateReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        UpdateNotifications.cancelAll(context)
        if (AppUpdater.consumeInstalledByUs(context)) UpdateNotifications.showUpdated(context)
    }
}
