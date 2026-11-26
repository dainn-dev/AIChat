package com.aichat.keyboard.theme

import dev.patrickgold.florisboard.lib.ext.ExtensionComponentName

/**
 * The AIChat keyboard themes, shipped as the asset extension `ime/theme/com.aichat.themes`.
 *
 * Both are iOS-style: white letter keys and gray function keys on a light-gray background by day, and the dark
 * counterpart by night. They are the defaults of `AppPrefs.theme.dayThemeId` / `nightThemeId`, and with the default
 * theme mode (follow system) the keyboard switches between them with the system dark mode.
 *
 * The stylesheets derive from the FlorisBoard day stylesheet. Change `tools/gen_aichat_themes.py` and regenerate
 * them rather than hand-editing both.
 */
object AiChatThemes {
    const val EXTENSION_ID = "com.aichat.themes"

    val DAY = ExtensionComponentName(extensionId = EXTENSION_ID, componentId = "aichat_ios_light")
    val NIGHT = ExtensionComponentName(extensionId = EXTENSION_ID, componentId = "aichat_ios_dark")
}
