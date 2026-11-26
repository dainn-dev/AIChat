/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Flattens a window's accessibility tree into [CapturedNode]s, bounded in size and time. */
internal object AccessibilityNodeMapper {
    /** A chat screen has a few hundred views; stop well past that. */
    private const val MAX_NODES = 2_000

    /**
     * Walks the visible part of the tree under [root] (depth-first) until [deadlineUptimeMs]. Password views are
     * mapped without their text. Every child obtained here is recycled; [root] stays with the caller.
     */
    fun collect(root: AccessibilityNodeInfo, deadlineUptimeMs: Long): List<CapturedNode> {
        val result = ArrayList<CapturedNode>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        val bounds = Rect()
        pushChildren(root, stack)
        result += map(root, bounds)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            try {
                if (result.size >= MAX_NODES || SystemClock.uptimeMillis() > deadlineUptimeMs) continue
                if (!node.isVisibleToUser) continue
                result += map(node, bounds)
                pushChildren(node, stack)
            } finally {
                recycle(node)
            }
        }
        return result
    }

    private fun pushChildren(node: AccessibilityNodeInfo, stack: ArrayDeque<AccessibilityNodeInfo>) {
        // Reverse order so the first child is visited first.
        for (i in node.childCount - 1 downTo 0) {
            node.getChild(i)?.let { stack.addLast(it) }
        }
    }

    private fun map(node: AccessibilityNodeInfo, bounds: Rect): CapturedNode {
        node.getBoundsInScreen(bounds)
        val password = node.isPassword
        return CapturedNode(
            text = if (password) null else node.text?.toString(),
            contentDescription = if (password) null else node.contentDescription?.toString(),
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
            className = node.className?.toString(),
            viewIdResourceName = node.viewIdResourceName,
            isEditable = node.isEditable,
            isPassword = password,
        )
    }

    /** Before Android 13 nodes come from a pool and must be returned; from 13 on recycle() is a no-op. */
    fun recycle(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
    }
}
