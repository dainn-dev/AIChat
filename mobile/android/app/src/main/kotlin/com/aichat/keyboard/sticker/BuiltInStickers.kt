package com.aichat.keyboard.sticker

/**
 * Sticker packs that ship with the keyboard. They are drawn at runtime ([StickerRenderer]), so there are no image
 * assets to license: the text pack is short Vietnamese chat phrases on iOS system colors, and the emoji pack draws
 * the device's own emoji font large.
 */
object BuiltInStickers {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF1C1C1E.toInt()

    // iOS system colors.
    private const val BLUE = 0xFF007AFF.toInt()
    private const val PINK = 0xFFFF2D55.toInt()
    private const val ORANGE = 0xFFFF9500.toInt()
    private const val GREEN = 0xFF34C759.toInt()
    private const val PURPLE = 0xFFAF52DE.toInt()
    private const val TEAL = 0xFF30B0C7.toInt()
    private const val YELLOW = 0xFFFFCC00.toInt()
    private const val RED = 0xFFFF3B30.toInt()
    private const val INDIGO = 0xFF5856D6.toInt()

    private data class Phrase(val id: String, val text: String, val background: Int, val foreground: Int = WHITE)

    private val phrases = listOf(
        Phrase("cam-on", "Cảm ơn\nnhiều!", BLUE),
        Phrase("ok-nha", "OK nha\n👌", GREEN),
        Phrase("haha", "Haha\n😂", YELLOW, BLACK),
        Phrase("yeu-lam", "Yêu lắm\n❤️", PINK),
        Phrase("cho-xiu", "Chờ xíu\nnha", ORANGE),
        Phrase("tuyet-voi", "Tuyệt\nvời!", PURPLE),
        Phrase("xin-loi", "Xin lỗi\nnha 🙏", TEAL),
        Phrase("ngu-ngon", "Ngủ ngon\n🌙", INDIGO),
        Phrase("dang-toi", "Đang tới\nđây!", RED),
        Phrase("vang-a", "Vâng ạ", BLUE),
        Phrase("chuc-mung", "Chúc mừng\n🎉", ORANGE),
        Phrase("an-com", "Ăn cơm\nchưa?", GREEN),
    )

    private val emojis = listOf(
        "joy" to "😂", "heart" to "❤️", "thumbs-up" to "👍", "smiling-hearts" to "🥰",
        "sob" to "😭", "pray" to "🙏", "fire" to "🔥", "party" to "🎉",
        "cool" to "😎", "thinking" to "🤔", "sleeping" to "😴", "pleading" to "🥺",
        "wave" to "👋", "hundred" to "💯", "angry" to "😡", "hug" to "🤗",
    )

    val packs: List<StickerPack> = listOf(
        StickerPack(
            id = "builtin-text",
            title = "Chữ",
            icon = "Aa",
            stickers = phrases.map { phrase ->
                Sticker(
                    id = "builtin-text.${phrase.id}",
                    description = phrase.text.replace('\n', ' '),
                    source = StickerSource.Text(phrase.text, phrase.background, phrase.foreground),
                )
            },
        ),
        StickerPack(
            id = "builtin-emoji",
            title = "Emoji lớn",
            icon = "😂",
            stickers = emojis.map { (id, emoji) ->
                Sticker(id = "builtin-emoji.$id", description = emoji, source = StickerSource.Emoji(emoji))
            },
        ),
    )
}
