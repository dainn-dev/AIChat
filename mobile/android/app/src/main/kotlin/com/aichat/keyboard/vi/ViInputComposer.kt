/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import dev.patrickgold.florisboard.ime.text.composing.Composer
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
}
