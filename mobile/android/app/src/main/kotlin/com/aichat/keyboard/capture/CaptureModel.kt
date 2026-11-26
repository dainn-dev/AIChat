/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

/**
 * One on-screen view as read from the accessibility tree, with no Android types so the parser stays a plain JVM
 * unit. Password nodes are mapped with null [text] and [contentDescription]: their content is never read.
 */
data class CapturedNode(
    val text: String?,
    val contentDescription: String?,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
    val className: String?,
    val viewIdResourceName: String?,
    val isEditable: Boolean,
    val isPassword: Boolean,
)

/** The visible views of a messenger's chat window, in screen coordinates. */
data class ChatWindowSnapshot(
    val packageName: String,
    val windowLeft: Int,
    val windowTop: Int,
    val windowRight: Int,
    val windowBottom: Int,
    val nodes: List<CapturedNode>,
)

/** What the accessibility service could read when asked. */
sealed interface SnapshotOutcome {
    /** The service is enabled in settings but not connected (yet), so nothing can be read. */
    data object NotRunning : SnapshotOutcome

    /** The app in front is not a supported messenger: nothing was read. */
    data object UnsupportedApp : SnapshotOutcome

    /** Reading the window failed or returned no tree. */
    data object Failed : SnapshotOutcome

    data class Snapshot(val snapshot: ChatWindowSnapshot) : SnapshotOutcome
}

/**
 * The chat apps the service may read, their `platform` id for `/v1/keyboard/reply` (WhatsApp Business shares
 * `whatsapp`, so per-contact memories stay together). Keep the package list in sync with
 * `res/xml/aichat_accessibility_service.xml`.
 */
enum class SupportedMessenger(val packageName: String, val platform: String) {
    MESSENGER("com.facebook.orca", "messenger"),
    ZALO("com.zing.zalo", "zalo"),
    WHATSAPP("com.whatsapp", "whatsapp"),
    WHATSAPP_BUSINESS("com.whatsapp.w4b", "whatsapp"),
    GMAIL("com.google.android.gm", "gmail"),
    GOOGLE_CHAT("com.google.android.apps.dynamite", "google_chat"),
    ;

    companion object {
        fun fromPackage(packageName: String?): SupportedMessenger? = entries.firstOrNull { it.packageName == packageName }
    }
}

/** Reads `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` without Android types. */
object CaptureServiceState {
    /**
     * True if [setting] (colon-separated flattened component names) lists [packageName]/[className]. The short form
     * `pkg/.Cls`, relative to the package, is accepted too.
     */
    fun isComponentEnabled(setting: String?, packageName: String, className: String): Boolean {
        if (setting.isNullOrBlank()) return false
        return setting.split(':').any { entry ->
            val parts = entry.trim().split('/')
            if (parts.size != 2) return@any false
            val (pkg, cls) = parts
            val fullClass = if (cls.startsWith('.')) pkg + cls else cls
            pkg == packageName && fullClass == className
        }
    }
}
