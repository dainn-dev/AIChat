package com.aichat.keyboard.sticker

import com.aichat.keyboard.sticker.StickerDelivery.Attempt
import dev.patrickgold.florisboard.ime.editor.InputAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StickerDeliveryTest {
    private val rendered = StickerDelivery.producibleMimes(originalMime = null, isAnimated = false)

    private fun plan(
        accepted: Array<String>?,
        producible: List<String> = rendered,
        canShare: Boolean = true,
        isPrivate: Boolean = false,
        hasText: Boolean = true,
    ) = StickerDelivery.plan(accepted, producible, canShare, isPrivate, hasText)

    // ---- producible formats ----

    @Test
    fun `rendered stickers can be produced as WebP first, then PNG`() {
        assertEquals(listOf(StickerMime.WEBP, StickerMime.PNG), rendered)
    }

    @Test
    fun `static files keep their own type first and can be converted to PNG`() {
        assertEquals(listOf(StickerMime.JPEG, StickerMime.PNG), StickerDelivery.producibleMimes(StickerMime.JPEG, false))
        assertEquals(listOf(StickerMime.WEBP, StickerMime.PNG), StickerDelivery.producibleMimes(StickerMime.WEBP, false))
        assertEquals(listOf(StickerMime.PNG), StickerDelivery.producibleMimes(StickerMime.PNG, false))
    }

    @Test
    fun `animated files are never converted`() {
        assertEquals(listOf(StickerMime.GIF), StickerDelivery.producibleMimes(StickerMime.GIF, true))
        assertEquals(listOf(StickerMime.WEBP), StickerDelivery.producibleMimes(StickerMime.WEBP, true))
    }

    // ---- rich content ----

    @Test
    fun `WebP is preferred when the field takes it, with PNG as the one retry`() {
        assertEquals(
            listOf(Attempt.Commit(StickerMime.WEBP), Attempt.Commit(StickerMime.PNG), Attempt.Share(StickerMime.PNG)),
            plan(arrayOf("image/webp", "image/png", "image/gif")),
        )
    }

    @Test
    fun `a field that takes any image gets WebP then PNG`() {
        assertEquals(
            listOf(Attempt.Commit(StickerMime.WEBP), Attempt.Commit(StickerMime.PNG)),
            plan(arrayOf("image/*"), canShare = false).filterIsInstance<Attempt.Commit>(),
        )
    }

    @Test
    fun `PNG is used when the field does not take WebP`() {
        assertEquals(
            listOf(Attempt.Commit(StickerMime.PNG), Attempt.Share(StickerMime.PNG)),
            plan(arrayOf("image/png", "image/gif")),
        )
    }

    @Test
    fun `a static file is converted to PNG only when the field does not take its own type`() {
        val jpeg = StickerDelivery.producibleMimes(StickerMime.JPEG, false)
        assertEquals(
            listOf(Attempt.Commit(StickerMime.PNG)),
            plan(arrayOf("image/png"), jpeg, canShare = false, hasText = false),
        )
        assertEquals(
            listOf(Attempt.Commit(StickerMime.JPEG), Attempt.Commit(StickerMime.PNG)),
            plan(arrayOf("image/jpeg", "image/png"), jpeg, canShare = false, hasText = false),
        )
    }

    @Test
    fun `an animated GIF is not committed to a field that only takes PNG, it is shared as GIF`() {
        val gif = StickerDelivery.producibleMimes(StickerMime.GIF, true)
        assertEquals(listOf(Attempt.Share(StickerMime.GIF)), plan(arrayOf("image/png"), gif, hasText = false))
    }

    @Test
    fun `at most two commit attempts are planned`() {
        val many = listOf(StickerMime.WEBP, StickerMime.PNG, StickerMime.JPEG)
        assertEquals(
            StickerDelivery.MAX_COMMIT_ATTEMPTS,
            plan(arrayOf("*/*"), many, canShare = false, hasText = false).size,
        )
    }

    // ---- share fallback ----

    @Test
    fun `a field that declares no image type shares the sticker instead of typing it`() {
        assertEquals(listOf(Attempt.Share(StickerMime.PNG)), plan(emptyArray()))
        assertEquals(listOf(Attempt.Share(StickerMime.PNG)), plan(null))
        assertEquals(listOf(Attempt.Share(StickerMime.PNG)), plan(arrayOf("text/plain", "video/*")))
    }

    @Test
    fun `a failed commit falls back to sharing`() {
        assertEquals(Attempt.Share(StickerMime.PNG), plan(arrayOf("image/png")).last())
    }

    @Test
    fun `text is typed only when nothing can be shared`() {
        assertEquals(listOf(Attempt.TypeText), plan(emptyArray(), canShare = false))
        assertEquals(
            listOf(Attempt.Commit(StickerMime.PNG), Attempt.TypeText),
            plan(arrayOf("image/png"), canShare = false),
        )
    }

    @Test
    fun `an image sticker with nowhere to go has an empty plan`() {
        val png = StickerDelivery.producibleMimes(StickerMime.PNG, false)
        assertTrue(plan(emptyArray(), png, canShare = false, hasText = false).isEmpty())
    }

    @Test
    fun `the share format prefers PNG, else the sticker's own type`() {
        assertEquals(StickerMime.PNG, StickerDelivery.shareMime(rendered))
        assertEquals(StickerMime.GIF, StickerDelivery.shareMime(listOf(StickerMime.GIF)))
        assertEquals(null, StickerDelivery.shareMime(emptyList()))
    }

    // ---- private fields ----

    @Test
    fun `private fields never share or type text`() {
        assertTrue(plan(emptyArray(), isPrivate = true).isEmpty())
        assertEquals(
            listOf(Attempt.Commit(StickerMime.WEBP), Attempt.Commit(StickerMime.PNG)),
            plan(arrayOf("image/*"), isPrivate = true),
        )
    }

    @Test
    fun `password, raw and incognito fields are private`() {
        val text = InputAttributes.Type.TEXT
        assertTrue(StickerDelivery.isPrivateField(text, InputAttributes.Variation.PASSWORD, false))
        assertTrue(StickerDelivery.isPrivateField(text, InputAttributes.Variation.VISIBLE_PASSWORD, false))
        assertTrue(StickerDelivery.isPrivateField(text, InputAttributes.Variation.WEB_PASSWORD, false))
        assertTrue(StickerDelivery.isPrivateField(InputAttributes.Type.NULL, InputAttributes.Variation.NORMAL, false))
        assertTrue(StickerDelivery.isPrivateField(text, InputAttributes.Variation.SHORT_MESSAGE, true))
    }

    @Test
    fun `an ordinary chat field is not private`() {
        assertFalse(StickerDelivery.isPrivateField(InputAttributes.Type.TEXT, InputAttributes.Variation.NORMAL, false))
        assertFalse(
            StickerDelivery.isPrivateField(InputAttributes.Type.TEXT, InputAttributes.Variation.SHORT_MESSAGE, false),
        )
    }
}
