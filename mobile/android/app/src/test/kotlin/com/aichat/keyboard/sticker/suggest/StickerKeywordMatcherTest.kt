package com.aichat.keyboard.sticker.suggest

import com.aichat.keyboard.sticker.StickerTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StickerKeywordMatcherTest {
    private fun tagsOf(before: String, after: String = ""): List<StickerTag>? =
        StickerKeywordMatcher.match(before, after)?.tags

    private fun assertTag(tag: StickerTag, vararg inputs: String) {
        for (input in inputs) {
            val tags = tagsOf(input)
            assertTrue(tags != null && tag in tags, "\"$input\" should suggest $tag but got $tags")
        }
    }

    private fun assertNoMatch(vararg inputs: String) {
        for (input in inputs) assertNull(StickerKeywordMatcher.match(input), "\"$input\" should not match")
    }

    @Test
    fun `every tag has keywords`() {
        assertEquals(StickerTag.entries.toSet(), StickerKeywordMatcher.coveredTags)
    }

    @Test
    fun `thanks in Vietnamese, English and teen code`() {
        assertTag(StickerTag.THANKS, "cảm ơn", "cám ơn", "camon", "cmon", "thanks", "thank", "tks", "thx", "ty", "Thank you")
    }

    @Test
    fun `missing tone marks and case are tolerated`() {
        assertTag(StickerTag.THANKS, "cam on", "CẢM ƠN", "Cam On", "cảm on")
        assertTag(StickerTag.GOODNIGHT, "ngu ngon", "Ngủ ngon")
        assertTag(StickerTag.LOVE, "yeu", "Yêu")
    }

    @Test
    fun `decomposed marks match like composed ones`() {
        val decomposed = java.text.Normalizer.normalize("cảm ơn", java.text.Normalizer.Form.NFD)
        assertTag(StickerTag.THANKS, decomposed)
    }

    @Test
    fun `laugh words and emoticons`() {
        assertTag(StickerTag.LAUGH, "haha", "hahaha", "hihi", "hehe", "kkk", "kkkkk", "=))", ":)))", "=)))", "lol", ":D")
    }

    @Test
    fun `the other example tags`() {
        assertTag(StickerTag.GOODNIGHT, "ngủ ngon", "g9", "gn")
        assertTag(StickerTag.OK, "ok", "oke", "okela", "okie", "được", "dc", "đc", "okeee")
        assertTag(StickerTag.LOVE, "yêu", "iu", "thương", "love", "<3")
        assertTag(StickerTag.SORRY, "xin lỗi", "sorry", "sr")
        assertTag(StickerTag.HELLO, "chào", "hello", "hi", "alo")
        assertTag(StickerTag.EAT, "ăn cơm", "ăn chưa", "đói", "an com chua")
        assertTag(StickerTag.SAD, "huhu", "buồn quá", ":((", "T_T")
        assertTag(StickerTag.MISS_YOU, "nhớ quá", "miss you")
        assertTag(StickerTag.BUSY, "đang bận", "bận")
        assertTag(StickerTag.THINKING, "hmm", "để xem")
        assertTag(StickerTag.SURPRISED, "wow", "trời ơi")
    }

    @Test
    fun `the keyword is found at the end of a longer message`() {
        assertTag(StickerTag.THANKS, "ok vậy mai gặp nhé, cảm ơn")
        assertEquals("cảm ơn", StickerKeywordMatcher.match("ok vậy mai gặp nhé, cảm ơn")?.trigger)
    }

    @Test
    fun `trailing particles, punctuation and a space are part of the trigger`() {
        assertEquals("cảm ơn nha", StickerKeywordMatcher.match("bạn ơi cảm ơn nha")?.trigger)
        assertEquals("cảm ơn nhiều nha!! ", StickerKeywordMatcher.match("cảm ơn nhiều nha!! ")?.trigger)
        assertEquals("ok rồi nhé", StickerKeywordMatcher.match("ok rồi nhé")?.trigger)
        assertEquals(listOf(StickerTag.OK), tagsOf("ok rồi nhé"))
    }

    @Test
    fun `the longest phrase wins`() {
        assertEquals(listOf(StickerTag.GOODNIGHT), tagsOf("good night"))
        assertEquals("chúc ngủ ngon", StickerKeywordMatcher.match("anh chúc ngủ ngon")?.trigger)
    }

    @Test
    fun `leading punctuation is not part of the trigger`() {
        assertEquals("ok", StickerKeywordMatcher.match("vậy...ok")?.trigger)
    }

    @Test
    fun `no match inside other words`() {
        assertNoMatch("book", "kok", "thanksgiving", "okra", "chaos", "khi", "hihihi1", "yeuu", "iuu", "camonn", "lolz")
        assertNoMatch("smoke", "hint", "goodness", "sorryy", "byebyebye")
    }

    @Test
    fun `different tone marks do not match`() {
        // đổi (change) is not đói (hungry); yếu (weak) is not yêu; tỷ is not ty; cháo is not chào.
        assertNoMatch("đổi", "yếu", "1 tỷ", "cháo", "nó", "dì")
    }

    @Test
    fun `accent-required keywords ignore their unmarked twins`() {
        // bạn/ban, nhỏ/nho, cho/chờ, doi/đổi, hai/hài, thuong/thường: typed without marks they mean something else.
        assertNoMatch("ban", "nho", "cho", "doi", "hai", "thuong", "dinh", "gioi", "da", "vang", "ha")
        assertNoMatch("nho qua", "doi qua")
    }

    @Test
    fun `a cursor inside a word suggests nothing`() {
        assertNull(StickerKeywordMatcher.match("ok", "ay"))
        assertTrue(StickerKeywordMatcher.match("ok", " bạn") != null)
    }

    @Test
    fun `a new line or a long gap after the keyword means the user moved on`() {
        assertNoMatch("cảm ơn\n", "cảm ơn          ")
        // A keyword on the previous line does not combine with the next one.
        assertEquals(listOf(StickerTag.OK), tagsOf("cảm\nok"))
    }

    @Test
    fun `empty, blank and punctuation-only text`() {
        assertNoMatch("", "   ", "...", "!!", "--")
    }

    @Test
    fun `particles alone do not match`() {
        assertNoMatch("nha", "rồi nhé", "quá")
    }

    @Test
    fun `a word cut by the read window is never matched`() {
        val longWord = "x".repeat(StickerKeywordMatcher.WINDOW + 10)
        // "…xxxxok" cut to its last 64 chars still ends in a non-word token; it must not become "ok".
        assertNoMatch(longWord + "ok")
        assertTag(StickerTag.OK, longWord + " ok")
    }
}
