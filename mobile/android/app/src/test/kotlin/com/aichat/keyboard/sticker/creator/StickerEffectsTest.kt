/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerEffectsTest {
    private val size = 512
    private val center = 256f
    private val radius = 100f
    private val subjectColor = 0xC8285A // (200, 40, 90)

    /** An opaque disc with a 1 px soft edge, centered with a wide transparent margin, like a composed cutout. */
    private fun disc(): StickerPixels {
        val argb = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val dx = x - center
            val dy = y - center
            val a = (radius + 0.5f - sqrt(dx * dx + dy * dy)).coerceIn(0f, 1f)
            val ai = (a * 255f).toInt()
            if (ai > 0) argb[y * size + x] = (ai shl 24) or subjectColor
        }
        return StickerPixels(size, size, argb)
    }

    private fun alpha(c: Int) = c ushr 24
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    private val borderPx = (DieCut.BORDER_FRACTION * size).toInt()

    /** A point on each side of the disc, halfway into the border. */
    private val borderPoints = listOf(
        center.toInt() + radius.toInt() + borderPx / 2 to center.toInt(),
        center.toInt() - radius.toInt() - borderPx / 2 to center.toInt(),
        center.toInt() to center.toInt() - radius.toInt() - borderPx / 2,
        center.toInt() to center.toInt() + radius.toInt() + borderPx / 2,
    )

    @Test
    fun `every effect keeps the size, leaves the input untouched and is deterministic`() {
        val input = disc()
        val before = input.argb.copyOf()
        for (effect in StickerEffect.entries) {
            val a = StickerEffects.apply(effect, input)
            val b = StickerEffects.apply(effect, input)
            assertEquals(size, a.width, "$effect")
            assertEquals(size, a.height, "$effect")
            assertContentEquals(before, input.argb, "$effect modified its input")
            assertContentEquals(a.argb, b.argb, "$effect is not deterministic")
        }
    }

    @Test
    fun `original is an exact copy`() {
        val input = disc()
        val out = StickerEffects.apply(StickerEffect.ORIGINAL, input)
        assertTrue(out !== input && out.argb !== input.argb)
        assertContentEquals(input.argb, out.argb)
    }

    @Test
    fun `outline draws an opaque white border just outside the subject`() {
        val out = StickerEffects.apply(StickerEffect.OUTLINE, disc())
        for ((x, y) in borderPoints) assertEquals(0xFFFFFFFF.toInt(), out[x, y], "at ($x, $y)")
    }

    @Test
    fun `outline border is about the specified width with an anti-aliased rim`() {
        val out = StickerEffects.apply(StickerEffect.OUTLINE, disc())
        val y = center.toInt()
        val edge = center.toInt() + radius.toInt()
        assertEquals(0xFFFFFFFF.toInt(), out[edge + borderPx - 1, y])
        // Beyond the border only the faint shadow remains: mostly transparent and dark.
        val beyond = out[edge + borderPx + 3, y]
        assertTrue(alpha(beyond) < 80, "alpha ${alpha(beyond)}")
        assertTrue(red(beyond) < 40)
    }

    @Test
    fun `outline leaves pixels far from the subject transparent`() {
        val out = StickerEffects.apply(StickerEffect.OUTLINE, disc())
        for ((x, y) in listOf(0 to 0, 511 to 511, 40 to 256, 256 to 470, 470 to 256)) {
            assertEquals(0, out[x, y], "at ($x, $y)")
        }
    }

    @Test
    fun `outline adds a soft shadow below the border`() {
        val out = StickerEffects.apply(StickerEffect.OUTLINE, disc())
        val belowBorder = center.toInt() + radius.toInt() + borderPx + 2
        val aboveBorder = center.toInt() - radius.toInt() - borderPx - 2
        val below = out[center.toInt(), belowBorder]
        val above = out[center.toInt(), aboveBorder]
        assertTrue(alpha(below) > 0 && red(below) == 0, "shadow below: ${Integer.toHexString(below)}")
        assertTrue(alpha(below) > alpha(above), "shadow should fall downwards")
        assertTrue(alpha(below) <= 255 * DieCut.SHADOW_OPACITY + 1)
    }

    @Test
    fun `outline keeps the subject colors`() {
        val input = disc()
        val out = StickerEffects.apply(StickerEffect.OUTLINE, input)
        for (y in 0 until size) for (x in 0 until size) {
            if (alpha(input[x, y]) == 255) assertEquals(input[x, y], out[x, y], "at ($x, $y)")
        }
    }

    @Test
    fun `a fully transparent cutout stays fully transparent with every effect`() {
        val empty = StickerPixels.transparent(size, size)
        for (effect in StickerEffect.entries) {
            assertTrue(StickerEffects.apply(effect, empty).argb.all { it == 0 }, "$effect drew something")
        }
    }

    @Test
    fun `a subject touching the canvas edge is clipped without failing`() {
        val w = 300
        val h = 200
        val argb = IntArray(w * h) { i -> if (i % w < 150) 0xFF3366CC.toInt() else 0 }
        val input = StickerPixels(w, h, argb)
        for (effect in StickerEffect.entries) {
            val out = StickerEffects.apply(effect, input)
            assertEquals(w, out.width)
            assertEquals(h, out.height)
        }
        val outline = StickerEffects.apply(StickerEffect.OUTLINE, input)
        assertEquals(0xFFFFFFFF.toInt(), outline[155, 100])
        assertEquals(0xFF3366CC.toInt(), outline[0, 0])
    }

    @Test
    fun `tiny images work`() {
        val input = StickerPixels(1, 1, intArrayOf(0xFF102030.toInt()))
        for (effect in StickerEffect.entries) assertEquals(1, StickerEffects.apply(effect, input).argb.size)
    }

    @Test
    fun `comic, puffy and shiny keep the white border but change the subject`() {
        val input = disc()
        val outline = StickerEffects.apply(StickerEffect.OUTLINE, input)
        for (effect in listOf(StickerEffect.COMIC, StickerEffect.PUFFY, StickerEffect.SHINY)) {
            val out = StickerEffects.apply(effect, input)
            for ((x, y) in borderPoints) {
                val c = out[x, y]
                assertEquals(255, alpha(c), "$effect border alpha at ($x, $y)")
                assertTrue(
                    red(c) >= 190 && green(c) >= 190 && blue(c) >= 190,
                    "$effect border not near white at ($x, $y): ${Integer.toHexString(c)}",
                )
            }
            var changed = 0
            var total = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (alpha(input[x, y]) != 255) continue
                total++
                if (out[x, y] != outline[x, y]) changed++
            }
            assertTrue(changed > total / 20, "$effect changed only $changed of $total subject pixels")
            assertEquals(0, out[0, 0], "$effect drew in the far corner")
        }
    }

    @Test
    fun `comic draws dark ink along the silhouette`() {
        val out = StickerEffects.apply(StickerEffect.COMIC, disc())
        val edge = center.toInt() + radius.toInt()
        val darkest = (edge - 2..edge + 2).minOf { x -> out[x, center.toInt()].let { red(it) + green(it) + blue(it) } }
        assertTrue(darkest < 150, "no ink at the edge (darkest sum $darkest)")
    }

    @Test
    fun `puffy is lit from the top left`() {
        val out = StickerEffects.apply(StickerEffect.PUFFY, disc())
        val inset = 6
        val topLeft = out[(center - radius * 0.7f).toInt() + inset, (center - radius * 0.7f).toInt() + inset]
        val bottomRight = out[(center + radius * 0.7f).toInt() - inset, (center + radius * 0.7f).toInt() - inset]
        assertTrue(red(topLeft) + green(topLeft) + blue(topLeft) > red(bottomRight) + green(bottomRight) + blue(bottomRight))
    }

    @Test
    fun `every effect renders a 512 px sticker quickly`() {
        val input = disc()
        for (effect in StickerEffect.entries) StickerEffects.apply(effect, input) // warm up the JIT
        for (effect in StickerEffect.entries) {
            val start = System.nanoTime()
            StickerEffects.apply(effect, input)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue(ms < 1_500, "$effect took $ms ms")
        }
    }
}
