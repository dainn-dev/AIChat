package com.aichat.keyboard.theme.background

import com.aichat.keyboard.theme.AiChatThemes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.florisboard.lib.snygg.SnyggStylesheet

class KeyboardBackgroundOverlayTest {
    // Unit tests run with the module directory (app/) as the working directory.
    private fun stylesheet(id: String) =
        File("src/main/assets/ime/theme/${AiChatThemes.EXTENSION_ID}/stylesheets/$id.json").readText()

    private val themeIds = listOf(AiChatThemes.DAY.componentId, AiChatThemes.NIGHT.componentId)
    private val file = "bg-0f8fad5b-d9cb-469f-a165-70867728950e.jpg"

    private fun parse(json: String) = Json.decodeFromString<JsonObject>(json)

    @Test
    fun `the overlaid AIChat stylesheets pass strict snygg parsing`() {
        for (id in themeIds) {
            val result = SnyggStylesheet.fromJson(KeyboardBackgroundOverlay.apply(stylesheet(id), file))
            assertTrue(result.isSuccess, "$id: ${result.exceptionOrNull()}")
        }
    }

    @Test
    fun `the window gets the photo, scaled to cover, and keeps its color as fallback`() {
        for (id in themeIds) {
            val original = parse(stylesheet(id))["window"]!!.jsonObject
            val window = parse(KeyboardBackgroundOverlay.apply(stylesheet(id), file))["window"]!!.jsonObject
            assertEquals("uri(`flex:/aichat-bg/$file`)", window["background-image"]!!.jsonPrimitive.content)
            assertEquals("crop", window["content-scale"]!!.jsonPrimitive.content)
            assertEquals(original["background"], window["background"], id)
        }
    }

    @Test
    fun `key surfaces turn translucent, pressed keys less so`() {
        val overlaid = parse(KeyboardBackgroundOverlay.apply(stylesheet(AiChatThemes.DAY.componentId), file))
        val defines = overlaid["@defines"]!!.jsonObject
        assertEquals("rgba(255,255,255,0.72)", defines["--surface"]!!.jsonPrimitive.content)
        assertEquals("rgba(255,255,255,0.72)", defines["--function-key"]!!.jsonPrimitive.content)
        assertEquals("rgba(209,211,217,0.90)", defines["--key-pressed"]!!.jsonPrimitive.content)
        // Popups and text keep the theme colors.
        assertEquals("#ffffff", defines["--popup-surface"]!!.jsonPrimitive.content)
        assertEquals("#000000", defines["--on-surface"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an invalid file name leaves the stylesheet untouched`() {
        val original = stylesheet(AiChatThemes.DAY.componentId)
        for (name in listOf("", "../../shared_prefs/x.jpg", "bg-x.jpg", "bg-0f8fad5b-d9cb-469f-a165-70867728950e.png")) {
            assertEquals(original, KeyboardBackgroundOverlay.apply(original, name), name)
        }
    }

    @Test
    fun `a stylesheet without window or defines still gets the photo`() {
        val json = """{"${'$'}schema":"${SnyggStylesheet.SCHEMA_V2}","key":{"background":"#ffffff"}}"""
        val out = KeyboardBackgroundOverlay.apply(json, file)
        assertTrue(SnyggStylesheet.fromJson(out).isSuccess)
        assertEquals("crop", parse(out)["window"]!!.jsonObject["content-scale"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed JSON is returned as is`() {
        assertEquals("{not json", KeyboardBackgroundOverlay.apply("{not json", file))
    }

    @Test
    fun `withAlpha converts opaque colors only`() {
        assertEquals("rgba(1,2,3,0.50)", KeyboardBackgroundOverlay.withAlpha("#010203", 0.5f))
        assertEquals("rgba(160,160,165,0.72)", KeyboardBackgroundOverlay.withAlpha("rgb(160,160,165)", 0.72f))
        assertNull(KeyboardBackgroundOverlay.withAlpha("rgba(0,0,0,0.35)", 0.5f))
        assertNull(KeyboardBackgroundOverlay.withAlpha("rgb(300,0,0)", 0.5f))
        assertNull(KeyboardBackgroundOverlay.withAlpha("var(--surface)", 0.5f))
    }

    @Test
    fun `only generated file names are accepted`() {
        assertTrue(KeyboardBackgroundStore.isValidFileName(file))
        assertFalse(KeyboardBackgroundStore.isValidFileName("bg-../../../etc/passwd.jpg"))
        assertFalse(KeyboardBackgroundStore.isValidFileName("x/$file"))
    }
}
