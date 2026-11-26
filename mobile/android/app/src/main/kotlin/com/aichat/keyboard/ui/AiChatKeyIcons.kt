/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.input.InputShiftState

/**
 * iOS-style shift key glyphs: outlined arrow (off), filled arrow (shift), filled arrow with a bar (caps lock).
 * The tint comes from the key's foreground (`SnyggIcon`), so the path colour here is only a placeholder.
 */
object AiChatKeyIcons {
    private const val VIEWPORT = 24f
    private const val STROKE_WIDTH = 1.8f
    private const val STEM_BOTTOM = 19.5f
    private const val STEM_BOTTOM_CAPS = 16.5f

    val ShiftOff: ImageVector by lazy { shift("AiChatShiftOff", filled = false, capsBar = false) }
    val ShiftOn: ImageVector by lazy { shift("AiChatShiftOn", filled = true, capsBar = false) }
    val CapsLock: ImageVector by lazy { shift("AiChatCapsLock", filled = true, capsBar = true) }

    fun forShiftState(state: InputShiftState): ImageVector = when (state) {
        InputShiftState.UNSHIFTED -> ShiftOff
        InputShiftState.CAPS_LOCK -> CapsLock
        else -> ShiftOn
    }

    private fun shift(name: String, filled: Boolean, capsBar: Boolean): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = VIEWPORT,
            viewportHeight = VIEWPORT,
        ).apply {
            path(
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE_WIDTH,
                strokeLineJoin = StrokeJoin.Round,
            ) { arrow(if (capsBar) STEM_BOTTOM_CAPS else STEM_BOTTOM) }
            if (capsBar) {
                path(fill = SolidColor(Color.Black)) {
                    moveTo(8.1f, 18.6f)
                    horizontalLineTo(15.9f)
                    verticalLineTo(20.6f)
                    horizontalLineTo(8.1f)
                    close()
                }
            }
        }.build()

    private fun PathBuilder.arrow(stemBottom: Float) {
        moveTo(12f, 3.5f)
        lineTo(20.5f, 12.5f)
        horizontalLineTo(15.9f)
        verticalLineTo(stemBottom)
        horizontalLineTo(8.1f)
        verticalLineTo(12.5f)
        horizontalLineTo(3.5f)
        close()
    }
}
