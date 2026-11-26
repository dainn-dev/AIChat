package com.aichat.keyboard.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.patrickgold.florisboard.ime.theme.ThemeExtension
import dev.patrickgold.florisboard.lib.ext.ExtensionJsonConfig
import org.florisboard.lib.snygg.SnyggStylesheet

/**
 * The AIChat theme extension must load: a stylesheet that fails strict parsing makes `ThemeManager` fall back to
 * the plain default theme at runtime, which no other test would notice.
 */
class AiChatThemesStylesheetTest {
    // Unit tests run with the module directory (app/) as the working directory.
    private val extensionDir = File("src/main/assets/ime/theme/${AiChatThemes.EXTENSION_ID}")

    private fun stylesheetFile(id: String) = File(extensionDir, "stylesheets/$id.json")

    @Test
    fun `the manifest decodes like ExtensionManager decodes it`() {
        val text = File(extensionDir, "extension.json").readText()
        val ext = ExtensionJsonConfig.decodeFromString(ThemeExtension.serializer(), text)
        assertEquals(AiChatThemes.EXTENSION_ID, ext.meta.id)
        assertEquals(
            setOf(AiChatThemes.DAY.componentId, AiChatThemes.NIGHT.componentId),
            ext.themes.map { it.id }.toSet(),
        )
    }

    @Test
    fun `the extension declares exactly the default day and night themes`() {
        val extension = Json.decodeFromString<JsonObject>(File(extensionDir, "extension.json").readText())
        assertEquals(AiChatThemes.EXTENSION_ID, extension["meta"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val themes = extension["themes"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["isNight"]!!.jsonPrimitive.content
        }
        assertEquals(
            mapOf(AiChatThemes.DAY.componentId to "false", AiChatThemes.NIGHT.componentId to "true"),
            themes,
        )
    }

    @Test
    fun `both stylesheets pass strict snygg parsing`() {
        for (id in listOf(AiChatThemes.DAY.componentId, AiChatThemes.NIGHT.componentId)) {
            val result = SnyggStylesheet.fromJson(stylesheetFile(id).readText())
            assertTrue(result.isSuccess, "$id: ${result.exceptionOrNull()}")
        }
    }

    @Test
    fun `every variable used is defined`() {
        for (id in listOf(AiChatThemes.DAY.componentId, AiChatThemes.NIGHT.componentId)) {
            val text = stylesheetFile(id).readText()
            val defines = Json.decodeFromString<JsonObject>(text)["@defines"]!!.jsonObject.keys
            val used = Regex("""var\((--[a-z-]+)\)""").findAll(text).map { it.groupValues[1] }.toSet()
            assertEquals(emptySet(), used - defines, id)
        }
    }
}
