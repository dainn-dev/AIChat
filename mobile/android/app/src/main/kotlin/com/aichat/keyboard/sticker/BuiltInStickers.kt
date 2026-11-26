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
        // Everyday chat phrases and teen code. Append only, never rename: ids are persisted (recent stickers).
        Phrase("oke-la", "Oke la\n👍", TEAL, tags = setOf(StickerTag.OK, StickerTag.YES)),
        Phrase("that-ha", "Thật hả?", ORANGE, tags = setOf(StickerTag.SURPRISED)),
        Phrase("gi-zi", "Gì zị?", PURPLE, tags = setOf(StickerTag.SURPRISED, StickerTag.THINKING)),
        Phrase("dinh", "Đỉnh!\n🔥", RED, tags = setOf(StickerTag.GREAT, StickerTag.COOL)),
        Phrase("hihi", "Hihi\n😆", PINK, tags = setOf(StickerTag.LAUGH)),
        Phrase("co-len", "Cố lên!\n💪", ORANGE, tags = setOf(StickerTag.CHEER)),
        Phrase("hong-sao-dau", "Hông sao\nđâu", TEAL, tags = setOf(StickerTag.OK)),
        Phrase("bye-bye", "Bye bye\n👋", INDIGO, tags = setOf(StickerTag.BYE)),
        Phrase("iu", "Iu\n💕", PINK, tags = setOf(StickerTag.LOVE)),
        Phrase("doi-qua", "Đói quá\n🍜", ORANGE, tags = setOf(StickerTag.EAT)),
        Phrase("dang-ban", "Đang bận\nxíu", INDIGO, tags = setOf(StickerTag.BUSY, StickerTag.WAIT)),
        Phrase("chao-nha", "Chào nha\n👋", BLUE, tags = setOf(StickerTag.HELLO)),
        Phrase("nho-qua", "Nhớ quá\n🥺", PURPLE, tags = setOf(StickerTag.MISS_YOU, StickerTag.LOVE)),
        Phrase("ha", "Hả??", YELLOW, BLACK, tags = setOf(StickerTag.SURPRISED)),
        Phrase("hmm", "Hmm…\n🤔", TEAL, tags = setOf(StickerTag.THINKING)),
        Phrase("duyet", "Duyệt!\n✅", GREEN, tags = setOf(StickerTag.YES, StickerTag.OK)),
        Phrase("khong-nha", "Không\nnha", RED, tags = setOf(StickerTag.NO)),
        Phrase("lam-on-ma", "Làm ơn\nmà 🥺", PINK, tags = setOf(StickerTag.PLEASE)),
        Phrase("buon-ghe", "Buồn ghê\n😢", INDIGO, tags = setOf(StickerTag.SAD)),
        Phrase("tuc-ghe", "Tức ghê\n😤", RED, tags = setOf(StickerTag.ANGRY)),
        Phrase("xiu", "Xỉu\n😵", PURPLE, tags = setOf(StickerTag.SURPRISED)),
        Phrase("thoi-xong", "Thôi\nxong!", ORANGE, tags = setOf(StickerTag.SAD, StickerTag.SURPRISED)),
        Phrase("di-choi", "Đi chơi\nkhông?", GREEN, tags = setOf(StickerTag.HELLO, StickerTag.CHEER)),
        Phrase("ve-chua", "Về chưa?", BLUE, tags = setOf(StickerTag.COMING, StickerTag.WAIT)),
        Phrase("toi-roi", "Tới rồi\nnè!", GREEN, tags = setOf(StickerTag.COMING)),
        Phrase("dung-roi", "Đúng\nrồi!", BLUE, tags = setOf(StickerTag.YES)),
        Phrase("hong-chiu", "Hông chịu\nđâu", ORANGE, tags = setOf(StickerTag.NO)),
        Phrase("thank-kiu", "Thank kiu\n🥰", TEAL, tags = setOf(StickerTag.THANKS)),
        Phrase("ngai-qua", "Ngại quá\n🙈", PINK, tags = setOf(StickerTag.SORRY)),
        Phrase("chill", "Chill\nthôi 😎", INDIGO, tags = setOf(StickerTag.COOL)),
    )

    /** A bubble color and the text color that reads on it. ARGB. */
    data class TextColors(val background: Int, val foreground: Int)

    /** The colors of the text pack, offered when the user makes their own text sticker. */
    val textColors: List<TextColors> = listOf(
        TextColors(BLUE, WHITE),
        TextColors(PINK, WHITE),
        TextColors(ORANGE, WHITE),
        TextColors(GREEN, WHITE),
        TextColors(PURPLE, WHITE),
        TextColors(TEAL, WHITE),
        TextColors(YELLOW, BLACK),
        TextColors(RED, WHITE),
        TextColors(INDIGO, WHITE),
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
