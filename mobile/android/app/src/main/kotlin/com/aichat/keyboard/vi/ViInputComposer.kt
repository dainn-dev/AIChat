/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import dev.patrickgold.florisboard.ime.keyboard.extCoreComposer
import dev.patrickgold.florisboard.ime.text.composing.Composer
import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Composer entry for the vi-input engine, referenced from `org.florisboard.composers/extension.json`
 * as `{ "$": "aichat-vi-input" }` and from the vi-VN subtype as `org.florisboard.composers:vi-input`.
 *
 * It is only a marker: FlorisBoard's composer interface is stateless, so `EditorInstance.determineComposer`
 * swaps it for the stateful [ViInputController.composerAdapter]. If that ever does not happen, it
 * behaves like the upstream `Appender` (inserts the key as is).
 */
@Serializable
@SerialName("aichat-vi-input")
object ViInputComposer : Composer {
    override val id = "vi-input"
    override val label = "Tiếng Việt (Telex / VNI)"
    override val toRead = 0

    override fun getActions(precedingText: String, toInsert: String): Pair<Int, String> = 0 to toInsert

    /**
     * Upstream's Telex rule table, removed in favour of vi-input. Subtypes saved before that change
     * still reference it; they are treated as vi-input instead of silently falling back to `Appender`.
     */
    val LEGACY_TELEX: ExtensionComponentName = extCoreComposer("telex")

    /** True if a subtype's composer [name] (resolved to [composer], if any) means "use vi-input". */
    fun isViInput(name: ExtensionComponentName, composer: Composer?): Boolean =
        composer is ViInputComposer || name == LEGACY_TELEX
}
