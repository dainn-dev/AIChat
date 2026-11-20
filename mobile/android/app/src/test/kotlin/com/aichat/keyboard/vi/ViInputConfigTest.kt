/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeJsonConfig
import dev.patrickgold.florisboard.ime.keyboard.KeyboardExtension
import dev.patrickgold.florisboard.ime.keyboard.extCoreComposer
import dev.patrickgold.florisboard.lib.ext.Extension
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ViInputConfigTest {
    private val assets = File("src/main/assets/ime/keyboard")

    private fun loadExtension(id: String): KeyboardExtension {
        val json = File(assets, "$id/extension.json").readText()
        return assertIs<KeyboardExtension>(ExtensionJsonConfig.decodeFromString<Extension>(json))
    }

    @Test
    fun `default subtypes are Vietnamese with vi-input then English`() {
        val subtypes = SubtypeJsonConfig.decodeFromString<List<Subtype>>(AiChatDefaultSubtypes.PREFERENCE_JSON)

        assertEquals(AiChatDefaultSubtypes.ALL, subtypes)
        assertEquals(listOf("vi-VN", "en-US"), subtypes.map { it.primaryLocale.languageTag() })
        assertEquals(extCoreComposer("vi-input"), subtypes[0].composer)
        assertEquals(extCoreComposer("appender"), subtypes[1].composer)
        assertTrue(subtypes.all { it.layoutMap.characters.componentId == "qwerty" })
    }

    @Test
    fun `composers extension ships vi-input and no telex table`() {
        val composers = loadExtension("org.florisboard.composers").composers

        assertEquals(listOf("appender", "hangul-unicode", "kana-unicode", "vi-input"), composers.map { it.id })
        assertIs<ViInputComposer>(composers.last())
    }

    @Test
    fun `vi-VN preset uses vi-input`() {
        val preset = loadExtension("org.florisboard.localization").subtypePresets
            .single { it.locale.languageTag() == "vi-VN" }

        assertEquals(extCoreComposer("vi-input"), preset.composer)
    }

    @Test
    fun `marker composer inserts keys unchanged`() {
        assertEquals(0 to "a", ViInputComposer.getActions("xyz", "a"))
    }
}
