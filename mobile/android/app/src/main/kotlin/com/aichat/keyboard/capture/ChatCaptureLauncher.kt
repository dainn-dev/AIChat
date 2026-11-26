/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import dev.patrickgold.florisboard.FlorisImeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Opens the chat-reader consent screen from the keyboard. Play requires the disclosure before the system
 * accessibility settings, so the keyboard never opens those directly.
 *
 * Settings has no deep links on purpose (an exported activity would let any app navigate it), so this is an
 * in-process request: the keyboard sets it, `FlorisAppActivity` consumes it and navigates. Other apps cannot set it.
 */
object ChatCaptureLauncher {
    private val _pendingConsent = MutableStateFlow(false)
    val pendingConsent: StateFlow<Boolean> = _pendingConsent.asStateFlow()

    /** From the keyboard: hide it and open settings on the consent screen. */
    fun openConsent() {
        _pendingConsent.value = true
        FlorisImeService.launchSettings()
    }

    /** From settings: true once per request. */
    fun consumePendingConsent(): Boolean {
        if (!_pendingConsent.value) return false
        _pendingConsent.value = false
        return true
    }
}
