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

    private data class Phrase(
        val id: String,
        val text: String,
        val background: Int,
        val foreground: Int = WHITE,
        val tags: Set<StickerTag> = emptySet(),
    )

    private val phrases = listOf(
        Phrase("cam-on", "Cảm ơn\nnhiều!", BLUE, tags = setOf(StickerTag.THANKS)),
        Phrase("ok-nha", "OK nha\n👌", GREEN, tags = setOf(StickerTag.OK, StickerTag.YES)),
        Phrase("haha", "Haha\n😂", YELLOW, BLACK, tags = setOf(StickerTag.LAUGH)),
        Phrase("yeu-lam", "Yêu lắm\n❤️", PINK, tags = setOf(StickerTag.LOVE)),
        Phrase("cho-xiu", "Chờ xíu\nnha", ORANGE, tags = setOf(StickerTag.WAIT)),
        Phrase("tuyet-voi", "Tuyệt\nvời!", PURPLE, tags = setOf(StickerTag.GREAT, StickerTag.CHEER)),
        Phrase("xin-loi", "Xin lỗi\nnha 🙏", TEAL, tags = setOf(StickerTag.SORRY)),
        Phrase("ngu-ngon", "Ngủ ngon\n🌙", INDIGO, tags = setOf(StickerTag.GOODNIGHT, StickerTag.BYE)),
        Phrase("dang-toi", "Đang tới\nđây!", RED, tags = setOf(StickerTag.COMING)),
        Phrase("vang-a", "Vâng ạ", BLUE, tags = setOf(StickerTag.YES, StickerTag.OK)),
        Phrase("chuc-mung", "Chúc mừng\n🎉", ORANGE, tags = setOf(StickerTag.CONGRATS, StickerTag.CHEER)),
        Phrase("an-com", "Ăn cơm\nchưa?", GREEN, tags = setOf(StickerTag.EAT)),
    )

    private val emojis = listOf(
        Triple("joy", "😂", setOf(StickerTag.LAUGH)),
        Triple("heart", "❤️", setOf(StickerTag.LOVE)),
        Triple("thumbs-up", "👍", setOf(StickerTag.OK, StickerTag.YES, StickerTag.GREAT)),
        Triple("smiling-hearts", "🥰", setOf(StickerTag.LOVE)),
        Triple("sob", "😭", setOf(StickerTag.SAD)),
        Triple("pray", "🙏", setOf(StickerTag.PLEASE, StickerTag.THANKS, StickerTag.SORRY)),
        Triple("fire", "🔥", setOf(StickerTag.GREAT, StickerTag.COOL)),
        Triple("party", "🎉", setOf(StickerTag.CONGRATS, StickerTag.CHEER)),
        Triple("cool", "😎", setOf(StickerTag.COOL)),
        Triple("thinking", "🤔", setOf(StickerTag.THINKING)),
        Triple("sleeping", "😴", setOf(StickerTag.GOODNIGHT)),
        Triple("pleading", "🥺", setOf(StickerTag.PLEASE, StickerTag.SAD)),
        Triple("wave", "👋", setOf(StickerTag.HELLO, StickerTag.BYE)),
        Triple("hundred", "💯", setOf(StickerTag.GREAT, StickerTag.YES)),
        Triple("angry", "😡", setOf(StickerTag.ANGRY)),
        Triple("hug", "🤗", setOf(StickerTag.LOVE, StickerTag.MISS_YOU, StickerTag.THANKS)),
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
                    tags = phrase.tags,
                )
            },
        ),
        StickerPack(
            id = "builtin-emoji",
            title = "Emoji lớn",
            icon = "😂",
            stickers = emojis.map { (id, emoji, tags) ->
                Sticker(id = "builtin-emoji.$id", description = emoji, source = StickerSource.Emoji(emoji), tags = tags)
            },
        ),
    )
}
