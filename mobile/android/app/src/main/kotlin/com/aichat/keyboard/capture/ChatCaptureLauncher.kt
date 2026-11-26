/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.capture

import android.os.SystemClock
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
    private val request = PendingConsentRequest(clock = SystemClock::elapsedRealtime)

    /** When the pending request was made (elapsed realtime), or null. */
    val pendingConsent: StateFlow<Long?> = request.pending

    /** From the keyboard: hide it and open settings on the consent screen. Nothing is left pending on failure. */
    fun openConsent() {
        request.open(launch = FlorisImeService::launchSettings)
    }

    /** From settings: true once per request, and only while it is fresh. */
    fun consumePendingConsent(): Boolean = request.consume()
}

/**
 * One "open the consent screen" request. It is set only when settings could actually be launched and expires after
 * [TTL_MS], so a request whose launch never reached settings cannot redirect a later, unrelated settings launch.
 * Pure (the [clock] is injected), unit-tested.
 */
internal class PendingConsentRequest(private val clock: () -> Long) {
    private val _pending = MutableStateFlow<Long?>(null)
    val pending: StateFlow<Long?> = _pending.asStateFlow()

    /** Marks the request, runs [launch] and keeps the mark only if it succeeded. Returns [launch]'s result. */
    fun open(launch: () -> Boolean): Boolean {
        // Marked before launching: the activity may start collecting before launch() returns.
        _pending.value = clock()
        val launched = launch()
        if (!launched) _pending.value = null
        return launched
    }

    fun consume(): Boolean {
        val at = _pending.value ?: return false
        _pending.value = null
        val age = clock() - at
        return age in 0..TTL_MS
    }

    companion object {
        const val TTL_MS = 10_000L
    }
}
