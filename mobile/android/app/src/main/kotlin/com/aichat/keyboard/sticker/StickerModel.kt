package com.aichat.keyboard.sticker

/** Where a sticker's image comes from. */
sealed interface StickerSource {
    /** A phrase drawn on a colored bubble. `\n` in [text] forces a line break. Colors are ARGB. */
    data class Text(val text: String, val background: Int, val foreground: Int) : StickerSource

    /** A single emoji drawn large on a transparent background. */
    data class Emoji(val emoji: String) : StickerSource

    /** An image file bundled in the app assets (see [AssetStickerPacks]). */
    data class Asset(val path: String) : StickerSource

    /** An image the user added from their gallery (see [UserStickerFiles]). */
    data class UserFile(val id: String) : StickerSource
}

data class Sticker(
    val id: String,
    /** Spoken by TalkBack and attached to the committed image as its label. */
    val description: String,
    val source: StickerSource,
    /** What the sticker says or means ([StickerTag]); drives sticker suggestions. Empty for untagged stickers. */
    val tags: Set<StickerTag> = emptySet(),
) {
    /** File-name-safe form of [id], used to name the image in the share cache. */
    val cacheKey: String
        get() = id.lowercase().replace(UNSAFE_CHARS, "_")

    private companion object {
        val UNSAFE_CHARS = Regex("[^a-z0-9_.-]")
    }
}

data class StickerPack(
    val id: String,
    val title: String,
    /** Short label of the pack tab: an emoji or one or two letters. */
    val icon: String,
    val stickers: List<Sticker>,
    /** The "Của tôi" pack: shows the add tile and allows deleting. */
    val isUserPack: Boolean = false,
)
