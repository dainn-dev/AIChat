/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.suggest

import com.aichat.keyboard.sticker.StickerTag
import com.aichat.keyboard.sticker.StickerTag.ANGRY
import com.aichat.keyboard.sticker.StickerTag.BUSY
import com.aichat.keyboard.sticker.StickerTag.BYE
import com.aichat.keyboard.sticker.StickerTag.CHEER
import com.aichat.keyboard.sticker.StickerTag.COMING
import com.aichat.keyboard.sticker.StickerTag.CONGRATS
import com.aichat.keyboard.sticker.StickerTag.COOL
import com.aichat.keyboard.sticker.StickerTag.EAT
import com.aichat.keyboard.sticker.StickerTag.GOODNIGHT
import com.aichat.keyboard.sticker.StickerTag.GREAT
import com.aichat.keyboard.sticker.StickerTag.HELLO
import com.aichat.keyboard.sticker.StickerTag.LAUGH
import com.aichat.keyboard.sticker.StickerTag.LOVE
import com.aichat.keyboard.sticker.StickerTag.MISS_YOU
import com.aichat.keyboard.sticker.StickerTag.NO
import com.aichat.keyboard.sticker.StickerTag.OK
import com.aichat.keyboard.sticker.StickerTag.PLEASE
import com.aichat.keyboard.sticker.StickerTag.SAD
import com.aichat.keyboard.sticker.StickerTag.SORRY
import com.aichat.keyboard.sticker.StickerTag.SURPRISED
import com.aichat.keyboard.sticker.StickerTag.THANKS
import com.aichat.keyboard.sticker.StickerTag.THINKING
import com.aichat.keyboard.sticker.StickerTag.WAIT
import com.aichat.keyboard.sticker.StickerTag.YES

/**
 * Vietnamese and English keywords (incl. teen code and abbreviations) for [StickerKeywordMatcher]. Phrases have 1-3
 * words, are written with their proper marks and are matched whole-word, case-insensitively.
 *
 * Put a phrase under `strict(...)` when its unmarked form is a common, different word: "bận" (busy) without marks is
 * "ban", which is also how people type "bạn" (friend).
 */
internal object StickerKeywords {
    class Entry(val tags: List<StickerTag>, val accentRequired: Boolean, val phrases: List<String>)

    val entries: List<Entry> = buildList {
        fun loose(tag: StickerTag, vararg phrases: String) = add(Entry(listOf(tag), false, phrases.toList()))
        fun strict(tag: StickerTag, vararg phrases: String) = add(Entry(listOf(tag), true, phrases.toList()))

        loose(
            THANKS,
            "cảm ơn", "cám ơn", "cảm ơn nhiều", "cám ơn nhiều", "cảm ơn bạn", "cám ơn bạn", "cảm ơn anh", "cảm ơn em",
            "cảm ơn chị", "camon", "cmon", "thanks", "thank", "thank you", "thank u", "thanks you", "thanks a lot",
            "many thanks", "tks", "thks", "thx", "ty", "tysm",
        )
        loose(
            OK,
            "ok", "okay", "oke", "okê", "okela", "okla", "okie", "oki", "okii", "okey", "oce", "được", "được rồi",
            "dc", "đc", "đc rồi", "ổn rồi", "duyệt",
        )
        strict(OK, "ổn")
        loose(
            YES,
            "ừ", "ừm", "ừa", "ừ ừ", "uh", "uhm", "um", "uk", "ukm", "ừk", "yes", "yep", "yeah", "yup", "có chứ",
            "đúng rồi", "chuẩn", "chuẩn luôn", "vâng ạ", "dạ vâng",
        )
        strict(YES, "vâng", "dạ")
        loose(
            NO,
            "không đâu", "ko đâu", "k đâu", "hông đâu", "không muốn", "không được", "ko được", "no", "nope", "nah",
            "no way", "khum", "hong", "hok", "hem", "thôi khỏi",
        )
        strict(NO, "hông", "khỏi")
        loose(LAUGH, "lol", "lmao", "rofl", "buồn cười", "mắc cười", "cười xỉu", "hài vãi", "haha")
        strict(LAUGH, "hài")
        loose(
            LOVE,
            "yêu", "iu", "yêu lắm", "yêu quá", "yêu em", "yêu anh", "yêu bạn", "iu nhiều", "love", "love you",
            "love u", "luv", "ily", "i love you", "thương lắm",
        )
        strict(LOVE, "thương", "thương quá")
        loose(
            WAIT,
            "đợi chút", "chờ chút", "đợi xíu", "chờ xíu", "đợi tí", "chờ tí", "đợi đã", "chờ đã", "đợi mình",
            "chờ mình", "wait", "wait a sec", "wait a minute", "hold on", "lát nữa", "một chút", "1 chút", "1 xíu",
            "1 tí",
        )
        strict(WAIT, "đợi", "chờ")
        loose(
            GREAT,
            "tuyệt", "tuyệt vời", "tuyệt quá", "quá đỉnh", "đỉnh quá", "đỉnh thật", "xịn xò", "giỏi quá", "hay quá",
            "great", "awesome", "nice", "good", "good job", "amazing", "perfect", "excellent", "10 điểm",
        )
        strict(GREAT, "đỉnh", "xịn", "xịn quá", "giỏi")
        loose(SORRY, "xin lỗi", "sorry", "sr", "sry", "soz", "xl", "lỗi tôi", "lỗi mình", "my bad", "thông cảm")
        loose(
            GOODNIGHT,
            "ngủ ngon", "chúc ngủ ngon", "g9", "gn", "good night", "goodnight", "nite", "night night", "ngủ thôi",
            "đi ngủ", "đi ngủ đây", "ngủ đây",
        )
        loose(
            COMING,
            "đang tới", "đang đến", "tới liền", "tới ngay", "đến ngay", "đến liền", "ra liền", "ra ngay", "xuống liền",
            "tới nè", "tới rồi", "đến rồi", "coming", "on my way", "omw", "otw",
        )
        loose(
            CONGRATS,
            "chúc mừng", "congrats", "congratulations", "congratz", "chúc mừng sinh nhật", "sinh nhật vui vẻ",
            "hpbd", "hbd", "happy birthday", "mừng quá",
        )
        loose(
            HELLO,
            "chào", "xin chào", "chào bạn", "chào anh", "chào em", "chào chị", "hello", "hi", "hey", "helo", "hellu",
            "alo", "a lô",
        )
        loose(
            BYE,
            "bye", "bye bye", "bb", "bai", "bái bai", "pp", "tạm biệt", "goodbye", "see you", "see ya", "cya",
            "gặp sau", "hẹn gặp lại",
        )
        loose(
            SAD,
            "buồn", "buồn quá", "buồn ghê", "chán", "chán quá", "sad", "so sad", "hic", "hix", "khóc", "muốn khóc",
            "tủi thân",
        )
        loose(ANGRY, "tức quá", "tức ghê", "giận rồi", "bực", "bực mình", "bực quá", "điên quá", "angry", "mad")
        strict(ANGRY, "tức", "giận", "cáu", "điên", "hứ", "hừ")
        loose(
            SURPRISED,
            "trời ơi", "trời", "trời đất", "omg", "oh my god", "thật á", "thật hả", "thật không", "thiệt hả", "what",
            "sao cơ", "gì cơ",
        )
        strict(SURPRISED, "ồ", "hả", "vãi")
        loose(
            CHEER,
            "cố lên", "cố gắng", "cố gắng lên", "fighting", "fighto", "go go", "you can do it", "chiến thôi",
            "quẩy lên",
        )
        loose(BUSY, "đang bận", "busy", "đang họp", "đang làm", "đang làm việc", "bận lắm")
        strict(BUSY, "bận", "bận rồi", "bận quá", "bận xíu")
        loose(MISS_YOU, "nhớ bạn", "nhớ em", "nhớ anh", "miss you", "miss u", "miss ya", "missing you")
        strict(MISS_YOU, "nhớ", "nhớ quá", "nhớ ghê", "nhớ lắm", "nhớ nhiều")
        loose(
            THINKING,
            "để xem", "để nghĩ", "để tính", "để coi", "suy nghĩ", "suy nghĩ đã", "thinking", "let me think",
            "không biết nữa", "chưa biết",
        )
        loose(COOL, "cool", "ngầu", "ngầu quá", "chất lừ", "đẹp trai", "cool ngầu", "swag")
        strict(COOL, "chất", "chất quá")
        loose(
            PLEASE,
            "please", "pls", "plz", "làm ơn", "năn nỉ", "đi mà", "giúp với", "giúp mình với", "xin đó",
        )
        loose(
            EAT,
            "ăn cơm", "ăn cơm chưa", "ăn chưa", "ăn gì", "ăn gì chưa", "ăn sáng", "ăn trưa", "ăn tối", "đi ăn",
            "đói bụng", "măm măm", "eat", "hungry", "lunch", "dinner",
        )
        strict(EAT, "đói", "đói quá", "đói rồi")
    }

    /** Single-word patterns, matched against the word without marks. */
    val patterns: List<Pair<String, StickerTag>> = listOf(
        "(?:ha){2,}h?|(?:he){2,}h?|(?:hi){2,}h?|(?:hj){2,}|k{3,}|[=:]-?\\){2,}|:-?d+|xd+|[=:]\\]{2,}" to LAUGH,
        "hu(?:hu)+|hi+c+|hi+x+|[:;][-']?\\(+|t[_.]t" to SAD,
        "<3+" to LOVE,
        "h+m{2,}|u?hm{2,}" to THINKING,
        "w+o+w+|:-?o+|o+m+g+" to SURPRISED,
        "o+k+(?:e+|ie+|i+)" to OK,
        "pl[sz]{2,}" to PLEASE,
    )

    /** Words that may follow a keyword without changing its meaning ("cảm ơn nha", "ok rồi"). */
    val particles: List<String> = listOf(
        "nha", "nhé", "nhe", "nhá", "nhen", "nghen", "hen", "ạ", "à", "a", "nè", "nhiều", "lắm", "quá", "luôn", "ghê",
        "nhỉ", "đó", "đây", "rồi", "nka", "nhaa",
    )
}
