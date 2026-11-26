/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

/** An app whose activity handles `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`. */
data class RecognizerCandidate(val packageName: String, val isSystemApp: Boolean)

/**
 * Which speech screen may hand us text to commit. `ACTION_RECOGNIZE_SPEECH` is an implicit intent, so any
 * installed app could answer it and return arbitrary text; only the system's own recognizers are trusted. Pure.
 */
object RecognizerTrust {
    /**
     * The package to pin the intent to, or null when no candidate is trusted. The package of the default
     * recognition service (chosen in the system settings) wins, then the first system app.
     */
    fun pickTrusted(candidates: List<RecognizerCandidate>, defaultServicePackage: String?): String? =
        candidates.firstOrNull { !defaultServicePackage.isNullOrEmpty() && it.packageName == defaultServicePackage }
            ?.packageName
            ?: candidates.firstOrNull { it.isSystemApp }?.packageName

    /** Package of a flattened component name (`Settings.Secure` `voice_recognition_service`: `pkg/cls`). */
    fun packageOfComponent(flattened: String?): String? {
        val slash = flattened?.indexOf('/') ?: return null
        return if (slash > 0) flattened.substring(0, slash) else null
    }
}
