/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.sticker.creator

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The per-effect parts of [StickerEffects]; the die-cut border and shadow come from [DieCut]. */
internal object StickerLooks {
    // --- COMIC -----------------------------------------------------------------------------------------------------

    /** Levels per channel after posterizing: few enough to read as flat comic fills, enough to keep skin tones. */
    private const val POSTER_LEVELS = 5
    private const val COMIC_SATURATION = 1.3f

    /** Ink appears between these Sobel magnitudes (0..1); below, texture noise stays clean. */
    private const val INK_LOW = 0.10f
    private const val INK_HIGH = 0.24f
    private const val INK_OPACITY = 0.92f
    private const val INK_R = 0.10f
    private const val INK_G = 0.09f
    private const val INK_B = 0.12f

    /**
     * Posterized, slightly more saturated copy of [src]. Colors are blurred (≈2 px) first so photo noise does not turn
     * into speckled band edges; alpha is kept exactly so the silhouette does not move.
     */
    fun comicSubject(src: FloatImage): FloatImage {
        val w = src.width
        val h = src.height
        val br = ImageFilters.boxBlur(src.r, w, h, 1, 3)
        val bg = ImageFilters.boxBlur(src.g, w, h, 1, 3)
        val bb = ImageFilters.boxBlur(src.b, w, h, 1, 3)
        val ba = ImageFilters.boxBlur(src.a, w, h, 1, 3)
        val out = FloatImage(w, h)
        val steps = (POSTER_LEVELS - 1).toFloat()
        for (i in src.a.indices) {
            val a = src.a[i]
            if (a <= 0f) continue
            var r: Float
            var g: Float
            var b: Float
            if (ba[i] > 1e-4f) {
                val inv = 1f / ba[i]
                r = br[i] * inv
                g = bg[i] * inv
                b = bb[i] * inv
            } else {
                val inv = 1f / a
                r = src.r[i] * inv
                g = src.g[i] * inv
                b = src.b[i] * inv
            }
            val l = ColorMath.luminance(r, g, b)
            r = ColorMath.clamp01(l + (r - l) * COMIC_SATURATION)
            g = ColorMath.clamp01(l + (g - l) * COMIC_SATURATION)
            b = ColorMath.clamp01(l + (b - l) * COMIC_SATURATION)
            out.a[i] = a
            out.r[i] = (r * steps).roundToInt() / steps * a
            out.g[i] = (g * steps).roundToInt() / steps * a
            out.b[i] = (b * steps).roundToInt() / steps * a
        }
        return out
    }

    /**
     * Ink line coverage: Sobel edges of the luminance (as if the subject sat on the white border, so dark-on-white
     * silhouettes get a line) and of the alpha (so light silhouettes do too), thickened by a pixel. Limited to the
     * sticker so the shadow stays clean.
     */
    fun comicInk(src: FloatImage, cut: DieCut): FloatArray {
        val w = src.width
        val h = src.height
        val lum = FloatArray(w * h)
        for (i in lum.indices) lum[i] = ColorMath.luminance(src.r[i], src.g[i], src.b[i]) + 1f - src.a[i]
        val edges = ImageFilters.sobelMagnitude(ImageFilters.boxBlur(lum, w, h, 1, 2), w, h)
        val alphaEdges = ImageFilters.sobelMagnitude(cut.subjectAlpha, w, h)
        val ink = FloatArray(w * h)
        for (i in ink.indices) ink[i] = ColorMath.smoothstep(INK_LOW, INK_HIGH, max(edges[i], alphaEdges[i]))
        val thick = ImageFilters.dilate3x3(ink, w, h)
        for (i in thick.indices) thick[i] *= cut.border[i] * INK_OPACITY
        return thick
    }

    fun drawInk(canvas: FloatImage, ink: FloatArray) = canvas.fillOver(ink, INK_R, INK_G, INK_B)

    // --- PUFFY -----------------------------------------------------------------------------------------------------

    /** Dome radii (share of the image size): the whole sticker rounds off quickly, the subject swells more. */
    private const val STICKER_DOME_FRACTION = 0.04f
    private const val SUBJECT_DOME_FRACTION = 0.10f
    private const val STICKER_DOME_HEIGHT = 0.5f
    private const val SUBJECT_DOME_HEIGHT = 0.7f
    private const val DIFFUSE = 0.55f
    private const val SPECULAR = 0.45f
    private const val SHININESS = 28f

    /** The border gets this share of the lighting: shaded, but still reads as a white border. */
    private const val PUFFY_BORDER_WEIGHT = 0.6f

    // Light from the top left (image y points down), viewer straight on.
    private val LIGHT = normalize(-0.55f, -0.75f, 1f)
    private val HALF = normalize(LIGHT[0], LIGHT[1], LIGHT[2] + 1f)

    /**
     * Treats distance-to-edge as a height map (a circular dome profile that flattens after a radius, like an inflated
     * pillow), derives normals and applies diffuse + Blinn-Phong specular. Shading is relative to a flat surface, so
     * flat interiors keep their colors and only the rounded edges darken or catch the light.
     */
    fun puffy(canvas: FloatImage, cut: DieCut) {
        val w = cut.width
        val h = cut.height
        val n = w * h
        val stickerOutside = BooleanArray(n) { cut.border[it] < 0.5f }
        val subjectOutside = BooleanArray(n) { !cut.inside[it] }
        val dSticker = DistanceTransform.euclidean(stickerOutside, w, h)
        val dSubject = DistanceTransform.euclidean(subjectOutside, w, h)
        val rS = max(1f, STICKER_DOME_FRACTION * cut.size)
        val rI = max(1f, SUBJECT_DOME_FRACTION * cut.size)
        val height = FloatArray(n)
        for (i in 0 until n) {
            val hs = if (stickerOutside[i]) 0f else dome(dSticker[i], rS) * rS * STICKER_DOME_HEIGHT
            val hi = if (subjectOutside[i]) 0f else dome(dSubject[i], rI) * rI * SUBJECT_DOME_HEIGHT
            height[i] = hs + hi
        }
        // Smooths the dome's vertical start at the edge and the creases of the distance field's medial axis.
        val hm = ImageFilters.boxBlur(height, w, h, max(1, (0.006f * cut.size).roundToInt()), 2)

        val lx = LIGHT[0]
        val ly = LIGHT[1]
        val lz = LIGHT[2]
        val hx = HALF[0]
        val hy = HALF[1]
        val hz = HALF[2]
        val flatSpec = hz.pow(SHININESS)
        for (y in 0 until h) {
            val ym = max(y - 1, 0) * w
            val yp = min(y + 1, h - 1) * w
            for (x in 0 until w) {
                val i = y * w + x
                val a = canvas.a[i]
                val cov = cut.border[i]
                if (a <= 0f || cov <= 0f) continue
                val dx = (hm[y * w + min(x + 1, w - 1)] - hm[y * w + max(x - 1, 0)]) * 0.5f
                val dy = (hm[yp + x] - hm[ym + x]) * 0.5f
                val inv = 1f / sqrt(dx * dx + dy * dy + 1f)
                val nx = -dx * inv
                val ny = -dy * inv
                val nz = inv
                val shade = 1f + DIFFUSE * (nx * lx + ny * ly + nz * lz - lz)
                val nh = max(0f, nx * hx + ny * hy + nz * hz)
                val spec = SPECULAR * max(0f, nh.pow(SHININESS) - flatSpec)
                val s = cut.subjectAlpha[i]
                val weight = cov * (s + (1f - s) * PUFFY_BORDER_WEIGHT)
                val inva = 1f / a
                canvas.r[i] = lit(canvas.r[i] * inva, shade, spec, weight) * a
                canvas.g[i] = lit(canvas.g[i] * inva, shade, spec, weight) * a
                canvas.b[i] = lit(canvas.b[i] * inva, shade, spec, weight) * a
            }
        }
    }

    /** Circular profile: 0 at the edge, rising steeply, flat (1) from [radius] inwards. */
    private fun dome(d: Float, radius: Float): Float {
        val t = 1f - min(d / radius, 1f)
        return sqrt(max(0f, 1f - t * t))
    }

    private fun lit(c: Float, shade: Float, spec: Float, weight: Float): Float =
        ColorMath.mix(c, ColorMath.clamp01(c * shade + spec), weight)

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val inv = 1f / sqrt(x * x + y * y + z * z)
        return floatArrayOf(x * inv, y * inv, z * inv)
    }

    // --- SHINY -----------------------------------------------------------------------------------------------------

    /** Rainbow repeats along the diagonal (top-left to bottom-right). */
    private const val HUE_CYCLES = 1.3f
    private const val HUE_PHASE = 0.55f
    private const val SUBJECT_SOFT_LIGHT = 0.45f
    private const val SUBJECT_SCREEN = 0.12f

    /** A pastel tint on the white border; soft light or screen would leave pure white unchanged. */
    private const val BORDER_TINT = 0.2f

    /** Highlight streaks: center and half-width along the streak axis (0..1), and strength. */
    private val STREAKS = floatArrayOf(
        0.30f, 0.030f, 0.45f,
        0.37f, 0.010f, 0.35f,
        0.70f, 0.045f, 0.25f,
    )

    /**
     * Holographic foil: a diagonal rainbow soft-lit into the subject (keeps its shading) and tinted onto the border,
     * then a few soft white streaks screened over everything, across the opposite diagonal like light on foil.
     */
    fun shiny(canvas: FloatImage, cut: DieCut) {
        val w = cut.width
        val h = cut.height
        val hueScale = HUE_CYCLES / (w + h)
        val streakScale = 1f / (0.8f * w + h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val a = canvas.a[i]
                val cov = cut.border[i]
                if (a <= 0f || cov <= 0f) continue
                val hue = (x + y) * hueScale + HUE_PHASE
                val t = (0.8f * x + (h - 1 - y)) * streakScale
                var streak = 0f
                var k = 0
                while (k < STREAKS.size) {
                    val d = (t - STREAKS[k]) / STREAKS[k + 1]
                    streak += STREAKS[k + 2] * exp(-d * d)
                    k += 3
                }
                streak = ColorMath.clamp01(streak)
                val s = cut.subjectAlpha[i]
                val inva = 1f / a
                canvas.r[i] = foil(canvas.r[i] * inva, ColorMath.hueChannel(hue, 0), s, streak, cov) * a
                canvas.g[i] = foil(canvas.g[i] * inva, ColorMath.hueChannel(hue, 1), s, streak, cov) * a
                canvas.b[i] = foil(canvas.b[i] * inva, ColorMath.hueChannel(hue, 2), s, streak, cov) * a
            }
        }
    }

    private fun foil(c: Float, rainbow: Float, subject: Float, streak: Float, cov: Float): Float {
        var onSubject = ColorMath.mix(c, ColorMath.softLight(c, rainbow), SUBJECT_SOFT_LIGHT)
        onSubject = ColorMath.mix(onSubject, ColorMath.screen(onSubject, rainbow), SUBJECT_SCREEN)
        val onBorder = ColorMath.mix(c, rainbow, BORDER_TINT)
        val foiled = ColorMath.screen(ColorMath.mix(onBorder, onSubject, subject), streak)
        return ColorMath.mix(c, foiled, cov)
    }
}
