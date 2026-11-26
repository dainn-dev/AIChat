/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.R

/** "Cập nhật ứng dụng" notifications. Silently skipped when the user has not allowed notifications. */
internal object UpdateNotifications {
    private const val CHANNEL_ID = "aichat_updates"
    private const val ID_AVAILABLE = 7101
    private const val ID_CONFIRM = 7102
    private const val ID_UPDATED = 7103

    /** A newer version exists but is not downloaded (mobile data). Tapping opens the app's settings. */
    fun showAvailable(context: Context, release: AppRelease) = notify(
        context,
        ID_AVAILABLE,
        context.getString(R.string.aichat__update__notif_available_title, release.versionName),
        context.getString(R.string.aichat__update__notif_available_text),
        openApp(context),
    )

    /** Android asks the user to confirm the install; tapping opens the system confirmation. */
    fun showConfirm(context: Context, versionName: String, confirm: Intent) = notify(
        context,
        ID_CONFIRM,
        context.getString(R.string.aichat__update__notif_confirm_title, versionName),
        context.getString(R.string.aichat__update__notif_confirm_text),
        PendingIntent.getActivity(
            context,
            ID_CONFIRM,
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    fun showUpdated(context: Context) = notify(
        context,
        ID_UPDATED,
        context.getString(R.string.aichat__update__notif_updated_title, BuildConfig.VERSION_NAME),
        context.getString(R.string.aichat__update__notif_updated_text),
        openApp(context),
    )

    fun cancelAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(ID_AVAILABLE)
        manager.cancel(ID_CONFIRM)
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notify(context: Context, id: Int, title: String, text: String, tap: PendingIntent?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.aichat__update__channel))
                .build(),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }
}
