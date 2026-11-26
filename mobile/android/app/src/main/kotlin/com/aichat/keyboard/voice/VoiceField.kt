/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Fingerprint of the field the mic key was pressed in. Recognized text is committed only into a field with the
 * same fingerprint ([matches]), never into a private one ([isPrivate]). Pure (the Android constants are inlined),
 * built from an [EditorInfo] by [VoiceInput].
 */
data class VoiceField(
    val packageName: String?,
    val fieldId: Int,
    val fieldName: String?,
    val inputType: Int,
    val imeOptions: Int,
) {
    val inputClass: Int get() = inputType and InputType.TYPE_MASK_CLASS
    val inputVariation: Int get() = inputType and InputType.TYPE_MASK_VARIATION
    val imeAction: Int get() = imeOptions and EditorInfo.IME_MASK_ACTION

    /** A password field (any variant) or a raw editor (`TYPE_NULL`): dictated text must never go there. */
    val isPrivate: Boolean
        get() = when (inputClass) {
            InputType.TYPE_NULL -> true
            InputType.TYPE_CLASS_TEXT -> inputVariation in TextPasswordVariations
            InputType.TYPE_CLASS_NUMBER -> inputVariation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }

    /** Same app and same field: id, name, input class/variation and action (flags may change, e.g. caps). */
    fun matches(other: VoiceField): Boolean =
        packageName == other.packageName &&
            fieldId == other.fieldId &&
            fieldName == other.fieldName &&
            inputClass == other.inputClass &&
            inputVariation == other.inputVariation &&
            imeAction == other.imeAction

    /** Never prints the field's content (it has none); safe to log. */
    override fun toString(): String =
        "VoiceField(package=$packageName, id=$fieldId, name=$fieldName, type=$inputType, ime=$imeOptions)"

    companion object {
        private val TextPasswordVariations = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )

        fun of(info: EditorInfo?): VoiceField? = info?.let {
            VoiceField(it.packageName, it.fieldId, it.fieldName, it.inputType, it.imeOptions)
        }
    }
}
