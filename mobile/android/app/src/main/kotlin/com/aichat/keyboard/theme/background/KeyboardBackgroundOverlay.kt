package com.aichat.keyboard.theme.background

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.util.Locale

/**
 * Puts the user's background photo under the active keyboard theme, without making a new theme: `ThemeManager`
 * runs every loaded stylesheet through [apply], so the photo shows with the day and the night theme alike.
 *
 * - `window` (the only opaque container behind the keys) gets the photo as `background-image`, scaled to cover.
 *   Its own `background` color stays as the fallback while the photo loads or if the file is gone.
 * - The key surface colors in `@defines` become translucent, so the photo shows through the keys too. Pressed
 *   keys stay nearly opaque so a tap is still visible. Popups, text and icons keep the theme colors.
 *
 * Works on the JSON text (before `SnyggStylesheet.fromJson`), so it is pure Kotlin and unit-tested.
 */
object KeyboardBackgroundOverlay {
    /** Asset URI prefix that `FlorisAssetResolver` maps to [KeyboardBackgroundStore]'s directory. */
    const val URI_PREFIX = "flex:/${KeyboardBackgroundStore.URI_DIR}/"

    const val KEY_ALPHA = 0.72f
    const val PRESSED_KEY_ALPHA = 0.9f

    private val KEY_VARS = listOf("--surface", "--function-key")
    private val PRESSED_KEY_VARS = listOf("--key-pressed", "--function-key-pressed")

    private const val DEFINES = "@defines"
    private const val WINDOW = "window"

    private val json = Json { prettyPrint = false }

    /** The stylesheet JSON with [imageFile] as the keyboard background, or [stylesheetJson] unchanged if invalid. */
    fun apply(stylesheetJson: String, imageFile: String): String {
        if (!KeyboardBackgroundStore.isValidFileName(imageFile)) return stylesheetJson
        val root = runCatching { json.parseToJsonElement(stylesheetJson).jsonObject }.getOrNull()
            ?: return stylesheetJson

        val result = root.toMutableMap()
        result[WINDOW] = JsonObject(
            (root[WINDOW] as? JsonObject).orEmpty() + mapOf(
                "background-image" to JsonPrimitive("uri(`$URI_PREFIX$imageFile`)"),
                "content-scale" to JsonPrimitive("crop"),
            ),
        )
        (root[DEFINES] as? JsonObject)?.let { defines ->
            val vars = defines.toMutableMap()
            fun fade(names: List<String>, alpha: Float) {
                for (name in names) {
                    val color = (vars[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    withAlpha(color, alpha)?.let { vars[name] = JsonPrimitive(it) }
                }
            }
            fade(KEY_VARS, KEY_ALPHA)
            fade(PRESSED_KEY_VARS, PRESSED_KEY_ALPHA)
            result[DEFINES] = JsonObject(vars)
        }
        return json.encodeToString(JsonElement.serializer(), JsonObject(result))
    }

    private val HEX = Regex("""^#([0-9a-fA-F]{6})$""")
    private val RGB = Regex("""^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$""")

    /**
     * `#rrggbb` or `rgb(r,g,b)` as `rgba(r,g,b,alpha)`; null for anything else (already translucent colors,
     * dynamic Material You colors, variables), which then stay as they are.
     */
    internal fun withAlpha(color: String, alpha: Float): String? {
        val rgb = HEX.matchEntire(color.trim())?.let { match ->
            val v = match.groupValues[1].toInt(16)
            Triple((v shr 16) and 0xff, (v shr 8) and 0xff, v and 0xff)
        } ?: RGB.matchEntire(color.trim())?.let { match ->
            val (r, g, b) = match.destructured
            Triple(r.toInt(), g.toInt(), b.toInt()).takeIf { (r, g, b) -> r <= 255 && g <= 255 && b <= 255 }
        } ?: return null
        return "rgba(${rgb.first},${rgb.second},${rgb.third},${String.format(Locale.ROOT, "%.2f", alpha)})"
    }
}
