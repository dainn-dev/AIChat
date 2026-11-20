/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import com.aichat.vi.InputMethod
import com.aichat.vi.ToneStyle
import com.aichat.vi.VietnameseComposer

/** User settings for the Vietnamese engine ("Kiểu gõ", "Kiểu đặt dấu", "Tự khôi phục từ tiếng Anh"). */
data class ViInputSettings(
    val inputMethod: InputMethod = InputMethod.TELEX,
    val toneStyle: ToneStyle = ToneStyle.MODERN,
    val autoRestoreEnglish: Boolean = true,
) {
    fun createComposer(): VietnameseComposer = VietnameseComposer(inputMethod, toneStyle, autoRestoreEnglish)

    companion object {
        val DEFAULT = ViInputSettings()
    }
}
