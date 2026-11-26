/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

/** An app whose activity handles `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`. */
data class RecognizerCandidate(val packageName: String, val isSystemApp: Boolean)

/** An installed `android.speech.RecognitionService`; [component] is the flattened `pkg/cls` name. */
data class ServiceCandidate(val packageName: String, val component: String, val isSystemApp: Boolean)

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

    /**
     * Google's speech services. Updated from the Play Store, they lose the system flag on some phones (seen on vivo,
     * where no default recognition service is selected either), but they are the recognizers users expect.
     */
    private val TRUSTED_SERVICE_PACKAGES = setOf(
        "com.google.android.tts",
        "com.google.android.googlequicksearchbox",
    )

    /**
     * The recognition service to bind explicitly when the system has no default one selected: a system app first,
     * then a known Google speech service. Third-party services (any app can declare one) are never used, since they
     * would receive the user's speech and hand back the text we commit.
     */
    fun pickService(candidates: List<ServiceCandidate>): String? =
        candidates.firstOrNull { it.isSystemApp }?.component
            ?: candidates.firstOrNull { it.packageName in TRUSTED_SERVICE_PACKAGES }?.component

    /** Package of a flattened component name (`Settings.Secure` `voice_recognition_service`: `pkg/cls`). */
    fun packageOfComponent(flattened: String?): String? {
        val slash = flattened?.indexOf('/') ?: return null
        return if (slash > 0) flattened.substring(0, slash) else null
    }
}
