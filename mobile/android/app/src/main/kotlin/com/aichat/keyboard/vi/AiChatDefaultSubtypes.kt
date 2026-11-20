/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import dev.patrickgold.florisboard.ime.core.Subtype
import dev.patrickgold.florisboard.ime.core.SubtypeJsonConfig
import dev.patrickgold.florisboard.ime.core.SubtypeLayoutMap
import dev.patrickgold.florisboard.ime.keyboard.extCoreComposer
import dev.patrickgold.florisboard.ime.keyboard.extCoreCurrencySet
import dev.patrickgold.florisboard.ime.keyboard.extCoreLayout
import dev.patrickgold.florisboard.ime.keyboard.extCorePopupMapping
import dev.patrickgold.florisboard.lib.FlorisLocale

/**
 * The two subtypes AIChat Keyboard ships with: English and Tiếng Việt, both QWERTY. They are the
 * default value of the `localization__subtypes` preference, so the globe / language key toggles
 * between them from the first run. FlorisBoard persists the active subtype id, so the last choice
 * survives restarts.
 */
object AiChatDefaultSubtypes {
    /** Fixed ids so the defaults are stable across installs (upstream uses the creation time). */
    const val ENGLISH_ID = 1L
    const val VIETNAMESE_ID = 2L

    val ENGLISH = Subtype(
        id = ENGLISH_ID,
        primaryLocale = FlorisLocale.from("en", "US"),
        secondaryLocales = emptyList(),
        composer = extCoreComposer("appender"),
        currencySet = extCoreCurrencySet("dollar"),
        popupMapping = extCorePopupMapping("en"),
        layoutMap = SubtypeLayoutMap(characters = extCoreLayout("qwerty")),
    )

    val VIETNAMESE = Subtype(
        id = VIETNAMESE_ID,
        primaryLocale = FlorisLocale.from("vi", "VN"),
        secondaryLocales = emptyList(),
        composer = extCoreComposer(ViInputComposer.id),
        currencySet = extCoreCurrencySet("vietnamese_dong"),
        popupMapping = extCorePopupMapping("vi-VN"),
        layoutMap = SubtypeLayoutMap(characters = extCoreLayout("qwerty")),
    )

    val ALL: List<Subtype> = listOf(ENGLISH, VIETNAMESE)

    /** JSON in the format of the `localization__subtypes` preference. */
    val PREFERENCE_JSON: String by lazy { SubtypeJsonConfig.encodeToString(ALL) }
}
