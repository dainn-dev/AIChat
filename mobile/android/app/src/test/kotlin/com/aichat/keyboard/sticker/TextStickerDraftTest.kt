package com.aichat.keyboard.sticker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextStickerDraftTest {
    @Test
    fun `accepts up to the character and line limits`() {
        assertTrue(TextStickerDraft.accepts(""))
        assertTrue(TextStickerDraft.accepts("a".repeat(TextStickerDraft.MAX_CHARS)))
        assertFalse(TextStickerDraft.accepts("a".repeat(TextStickerDraft.MAX_CHARS + 1)))
        assertTrue(TextStickerDraft.accepts("a\nb\nc"))
        assertFalse(TextStickerDraft.accepts("a\nb\nc\nd"))
    }

    @Test
    fun `blank text is not savable`() {
        assertFalse(TextStickerDraft.isSavable(""))
        assertFalse(TextStickerDraft.isSavable("  \n "))
        assertTrue(TextStickerDraft.isSavable("Hihi"))
    }

    @Test
    fun `short text is kept as is`() {
        assertEquals("Hihi", TextStickerDraft.layout("Hihi"))
        assertEquals("Cảm ơn\nnhiều!", TextStickerDraft.layout("Cảm ơn\nnhiều!"))
    }

    @Test
    fun `layout trims lines, collapses spaces and drops blank lines`() {
        assertEquals("Ok\nnha", TextStickerDraft.layout("  Ok  \n\n   nha "))
        assertEquals("Đi đâu", TextStickerDraft.layout("Đi    đâu"))
        assertEquals("", TextStickerDraft.layout(" \n "))
    }

    @Test
    fun `layout wraps a long line at spaces`() {
        assertEquals("Hôm nay\nvui quá", TextStickerDraft.layout("Hôm nay vui quá"))
    }

    @Test
    fun `layout never makes more than three lines`() {
        val long = "một hai ba bốn năm sáu bảy tám chín mười"
        val result = TextStickerDraft.layout(long)
        assertEquals(TextStickerDraft.MAX_LINES, result.lines().size, result)
        assertEquals(long, result.replace('\n', ' '))
        val typed = "một hai ba bốn\nnăm sáu bảy\ntám chín"
        assertEquals(typed, TextStickerDraft.layout(typed))
    }

    @Test
    fun `layout keeps a long single word on its own line`() {
        assertEquals("Supercalifragilistic", TextStickerDraft.layout("Supercalifragilistic"))
    }
}
