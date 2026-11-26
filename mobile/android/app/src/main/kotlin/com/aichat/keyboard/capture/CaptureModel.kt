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

    /** The app in front cannot be read (AIChat itself): nothing was read. */
    data object UnsupportedApp : SnapshotOutcome

    /** Reading the window failed or returned no tree. */
    data object Failed : SnapshotOutcome

    data class Snapshot(val snapshot: ChatWindowSnapshot) : SnapshotOutcome
}

/**
 * Chat apps with a well-known `platform` id for `/v1/keyboard/reply` (WhatsApp Business shares `whatsapp`, so
 * per-contact memories stay together). Any other app can be read too; it gets [CapturePlatform.of] instead.
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

/** The `platform` id sent for a captured chat. */
object CapturePlatform {
    /** Matches the backend's `platform` pattern `[a-z0-9._-]{1,64}`. */
    private const val MAX_LENGTH = 64
    private val disallowed = Regex("[^a-z0-9._-]")

    /**
     * A known messenger's id, else the package name itself (lower-cased, limited to the backend pattern), so memories
     * of another chat app stay per app. Null when nothing usable is left.
     */
    fun of(packageName: String?): String? {
        SupportedMessenger.fromPackage(packageName)?.let { return it.platform }
        return packageName?.lowercase()?.replace(disallowed, "")?.take(MAX_LENGTH)?.ifEmpty { null }
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
