package com.aichat.keyboard.sticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Draws the built-in stickers ([StickerSource.Text], [StickerSource.Emoji]) as [SIZE_PX] square bitmaps with a
 * transparent background, the format chat apps expect for stickers.
 */
object StickerRenderer {
    const val SIZE_PX = 512

    // Text sticker: a rounded bubble with a white sticker outline and a soft drop shadow.
    private const val BUBBLE_INSET = 32f
    private const val BUBBLE_CORNER = 112f
    private const val OUTLINE_WIDTH = 14f
    private const val TEXT_PADDING = 44f
    private const val MAX_TEXT_SIZE = 150f
    private const val MIN_TEXT_SIZE = 36f
    private const val LINE_HEIGHT = 1.18f
    private const val MEASURE_SIZE = 100f
    private const val SHADOW_RADIUS = 16f
    private const val SHADOW_DY = 8f
    private const val SHADOW_COLOR = 0x40000000
    private const val OUTLINE_COLOR = 0xFFFFFFFF.toInt()

    private const val EMOJI_TEXT_SIZE = 360f

    /** The bitmap of a built-in sticker, or null for sources that are already image files. */
    fun render(source: StickerSource): Bitmap? = when (source) {
        is StickerSource.Text -> renderText(source)
        is StickerSource.Emoji -> renderEmoji(source)
        is StickerSource.Asset, is StickerSource.UserFile -> null
    }

    private fun newCanvas(): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        return bitmap to Canvas(bitmap)
    }

    private fun renderText(source: StickerSource.Text): Bitmap {
        val (bitmap, canvas) = newCanvas()
        val bubble = RectF(BUBBLE_INSET, BUBBLE_INSET, SIZE_PX - BUBBLE_INSET, SIZE_PX - BUBBLE_INSET)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = source.background
            setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, SHADOW_COLOR)
        }
        canvas.drawRoundRect(bubble, BUBBLE_CORNER, BUBBLE_CORNER, fill)
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_WIDTH
            color = OUTLINE_COLOR
        }
        canvas.drawRoundRect(bubble, BUBBLE_CORNER, BUBBLE_CORNER, outline)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = source.foreground
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // Some OEM system fonts (seen on vivo) have no bold face; embolden the strokes instead.
            isFakeBoldText = typeface?.isBold != true
            textAlign = Paint.Align.CENTER
            textSize = MEASURE_SIZE
        }
        val lines = source.text.split('\n')
        val widthsPerUnit = lines.map { paint.measureText(it) / MEASURE_SIZE }
        paint.textSize = StickerTextFit.fontSize(
            lineWidthsPerUnit = widthsPerUnit,
            lineHeightPerUnit = LINE_HEIGHT,
            box = bubble.width() - 2 * TEXT_PADDING,
            maxSize = MAX_TEXT_SIZE,
            minSize = MIN_TEXT_SIZE,
        )

        val lineHeight = paint.textSize * LINE_HEIGHT
        val metrics = paint.fontMetrics
        val glyphHeight = metrics.descent - metrics.ascent
        val blockTop = bubble.centerY() - lineHeight * lines.size / 2
        lines.forEachIndexed { index, line ->
            val baseline = blockTop + index * lineHeight + (lineHeight - glyphHeight) / 2 - metrics.ascent
            canvas.drawText(line, bubble.centerX(), baseline, paint)
        }
        return bitmap
    }

    private fun renderEmoji(source: StickerSource.Emoji): Bitmap {
        val (bitmap, canvas) = newCanvas()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = EMOJI_TEXT_SIZE
        }
        val metrics = paint.fontMetrics
        val baseline = SIZE_PX / 2f - (metrics.ascent + metrics.descent) / 2
        canvas.drawText(source.emoji, SIZE_PX / 2f, baseline, paint)
        return bitmap
    }
}
