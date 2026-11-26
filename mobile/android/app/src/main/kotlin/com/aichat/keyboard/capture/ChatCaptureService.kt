/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * AIChat's chat reader. It is idle by design: it ignores every accessibility event, keeps no history and stores
 * nothing. Only when the keyboard asks ([requestSnapshot], after the user taps "Gợi ý trả lời") does it read the
 * visible views of the chat app in front, and only if that app is a [SupportedMessenger].
 *
 * The keyboard runs in the same process, so the connected instance is reached through a static reference.
 */
class ChatCaptureService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: the service reads the screen only on request, never in the background.
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (instance === this) instance = null
    }

    /** Blocking (binder calls): run off the main thread. */
    internal fun takeSnapshot(): SnapshotOutcome {
        val target = findChatWindow() ?: return SnapshotOutcome.Failed
        val root = target.root
        try {
            if (SupportedMessenger.fromPackage(root.packageName?.toString()) == null) {
                return SnapshotOutcome.UnsupportedApp
            }
            val bounds = target.bounds
            val deadline = SystemClock.uptimeMillis() + WALK_BUDGET_MS
            val nodes = AccessibilityNodeMapper.collect(root, deadline)
            return SnapshotOutcome.Snapshot(
                ChatWindowSnapshot(
                    packageName = root.packageName.toString(),
                    windowLeft = bounds.left,
                    windowTop = bounds.top,
                    windowRight = bounds.right,
                    windowBottom = bounds.bottom,
                    nodes = nodes,
                ),
            )
        } finally {
            AccessibilityNodeMapper.recycle(root)
        }
    }

    private class ChatWindow(val root: AccessibilityNodeInfo, val bounds: Rect)

    /**
     * The app window behind the keyboard. The IME window is not an application window, so the focused (or else
     * active, or else topmost) application window is the chat app. Falls back to [getRootInActiveWindow].
     */
    private fun findChatWindow(): ChatWindow? {
        val allWindows = try {
            windows.orEmpty()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            Log.w(TAG, "Window list unavailable: ${e.javaClass.simpleName}")
            emptyList()
        }
        try {
            val appWindows = allWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val window = appWindows.firstOrNull { it.isFocused }
                ?: appWindows.firstOrNull { it.isActive }
                ?: appWindows.maxByOrNull { it.layer }
            val root = window?.root ?: rootInActiveWindow ?: return null
            val bounds = Rect()
            if (window != null) window.getBoundsInScreen(bounds) else root.getBoundsInScreen(bounds)
            if (bounds.isEmpty) root.getBoundsInScreen(bounds)
            return ChatWindow(root, bounds)
        } finally {
            recycleWindows(allWindows)
        }
    }

    /** Before Android 13 window infos come from a pool and must be returned. */
    private fun recycleWindows(list: List<AccessibilityWindowInfo>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            list.forEach { runCatching { it.recycle() } }
        }
    }

    companion object {
        private const val TAG = "ChatCaptureService"

        /** Upper bound for walking one window's tree; the provider's own timeout is longer. */
        private const val WALK_BUDGET_MS = 1_000L

        @Volatile
        private var instance: ChatCaptureService? = null

        /** True if the user turned the service on in the system accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val setting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            val component = ComponentName(context, ChatCaptureService::class.java)
            return CaptureServiceState.isComponentEnabled(setting, component.packageName, component.className)
        }

        /** Reads the chat window in front, off the main thread. Never throws. */
        suspend fun requestSnapshot(): SnapshotOutcome {
            val service = instance ?: return SnapshotOutcome.NotRunning
            return try {
                runInterruptible(Dispatchers.Default) { service.takeSnapshot() }
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Exception type only: the tree may hold chat text.
                Log.w(TAG, "Snapshot failed: ${e.javaClass.simpleName}")
                SnapshotOutcome.Failed
            }
        }
    }
}
