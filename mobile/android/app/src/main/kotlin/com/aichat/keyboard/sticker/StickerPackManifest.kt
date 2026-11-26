package com.aichat.keyboard.sticker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the `pack.json` of a bundled image sticker pack (`assets/ime/media/sticker/<pack>/pack.json`):
 *
 * ```json
 * { "id": "cats", "title": "Mèo", "icon": "🐱",
 *   "stickers": [ { "file": "hi.png", "description": "Mèo chào", "tags": ["hello"] } ] }
 * ```
 *
 * Files must sit directly in the pack folder and be PNG, WebP, GIF or JPEG; other entries are skipped.
 * `tags` is optional and holds [StickerTag] wire names; unknown names are ignored (a newer pack may know more tags).
 */
object StickerPackManifest {
    private val json = Json { ignoreUnknownKeys = true }

    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")

    @Serializable
    private data class Manifest(
        val id: String,
        val title: String,
        val icon: String = "",
        val stickers: List<Entry> = emptyList(),
    )

    @Serializable
    private data class Entry(
        val file: String,
        val description: String? = null,
        val tags: List<String> = emptyList(),
    )

    /** The pack, or null if [text] is not a valid manifest or lists no usable sticker. */
    fun parse(text: String, assetDir: String): StickerPack? {
        val manifest = runCatching { json.decodeFromString<Manifest>(text) }.getOrNull() ?: return null
        if (manifest.id.isBlank() || manifest.title.isBlank()) return null

        val stickers = manifest.stickers
            .filter { SAFE_FILE_NAME.matches(it.file) && StickerMime.forFileName(it.file) != null }
            .distinctBy { it.file } // sticker ids are grid keys and must be unique
            .map { entry ->
                Sticker(
                    id = "asset:${manifest.id}:${entry.file}",
                    description = entry.description?.takeIf { it.isNotBlank() } ?: manifest.title,
                    source = StickerSource.Asset("$assetDir/${entry.file}"),
                    tags = entry.tags.mapNotNull(StickerTag::fromWire).toSet(),
                )
            }
        if (stickers.isEmpty()) return null

        return StickerPack(
            id = "asset:${manifest.id}",
            title = manifest.title,
            icon = manifest.icon.ifBlank { manifest.title.take(2) },
            stickers = stickers,
        )
    }
}
