/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.aichat.keyboard.ai.AiAssist
import com.aichat.keyboard.ai.AiAssistState
import com.aichat.keyboard.ai.AiFeatureGate
import com.aichat.keyboard.ai.AiSmartbarLayout
import com.aichat.keyboard.ai.TranslatePreview
import com.aichat.keyboard.ai.keyboardString
import com.aichat.keyboard.ai.languageName
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import com.aichat.keyboard.sticker.suggest.rememberTypingStickerSuggestion
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.ime.nlp.NlpInlineAutofill
import dev.patrickgold.florisboard.ime.smartbar.SmartbarLayout
import dev.patrickgold.florisboard.nlpManager
import dev.patrickgold.jetpref.datastore.model.observeAsState
import org.florisboard.lib.android.AndroidVersion

/**
 * Whether the smartbar is shown right now ([AiChatSizing.shouldShowSmartbar] fed with live state). Used by both the
 * smartbar itself and `FlorisImeSizing.smartbarUiHeight()`, so the reserved height always matches what is drawn.
 */
@Composable
fun isSmartbarVisible(): Boolean {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val nlpManager by context.nlpManager()
    val candidates by nlpManager.activeCandidatesFlow.collectAsState()
    val inlineSuggestions by NlpInlineAutofill.suggestions.collectAsState()
    val enabled by prefs.smartbar.enabled.observeAsState()
    val layout by prefs.smartbar.layout.observeAsState()
    val aiController = remember { AiAssist.controller(context) }
    val aiEnabled by prefs.aichatAi.enabled.observeAsState()
    val rawAiState by aiController.state.collectAsState()
    val aiState = AiFeatureGate.panelState(aiEnabled, rawAiState)
    val stickerSuggestion = rememberTypingStickerSuggestion()
    return AiChatSizing.shouldShowSmartbar(
        enabled = enabled,
        suggestionsOnly = layout == SmartbarLayout.SUGGESTIONS_ONLY,
        // Sticker suggestions open the bar too (e.g. after "cảm ơn " when there are no word suggestions).
        hasSuggestions = candidates.isNotEmpty() || (AndroidVersion.ATLEAST_API30_R && inlineSuggestions.isNotEmpty()) ||
            stickerSuggestion != null,
        aiOpen = aiState != AiAssistState.Closed,
    )
}

/**
 * Rows of the smartbar's main row right now ([AiSmartbarLayout.mainRowCount] fed with live state): more than one
 * while the AI reply suggestions are shown. Used by both the smartbar and `FlorisImeSizing.smartbarUiHeight()`.
 */
@Composable
fun smartbarMainRowCount(): Int {
    val prefs by FlorisPreferenceStore
    val context = LocalContext.current
    val layout by prefs.smartbar.layout.observeAsState()
    val aiController = remember { AiAssist.controller(context) }
    val aiEnabled by prefs.aichatAi.enabled.observeAsState()
    val rawAiState by aiController.state.collectAsState()
    val aiState = AiFeatureGate.panelState(aiEnabled, rawAiState)
    return AiSmartbarLayout.mainRowCount(
        suggestionsOnly = layout == SmartbarLayout.SUGGESTIONS_ONLY,
        state = aiState,
        translationWraps = rememberTranslationWraps(aiState),
    )
}

/**
 * Whether the live translation needs a second line, measured at the AI text size against the keyboard width.
 * While the next translation loads, the last answer is kept so the bar does not jump while typing.
 */
@Composable
private fun rememberTranslationWraps(state: AiAssistState): Boolean {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val context = LocalContext.current
    val last = remember { booleanArrayOf(false) }
    val translating = state as? AiAssistState.Translating ?: return true
    val style = TextStyle(fontSize = AiSmartbarLayout.TEXT_SIZE_SP.sp)
    fun width(text: String): Float = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat()
    val chip = width("${languageName(translating.targetLang)} ▾")
    val rowWidth = context.resources.displayMetrics.widthPixels.toFloat()
    val chrome = with(density) { AiSmartbarLayout.TRANSLATE_ROW_CHROME_DP.dp.toPx() }
    val undo = keyboardString(R.string.aichat__ai__undo)
    val wraps = when (val preview = translating.preview) {
        TranslatePreview.Idle -> false
        is TranslatePreview.Loading -> return last[0]
        is TranslatePreview.Failed -> true
        is TranslatePreview.Ready ->
            AiSmartbarLayout.translationWraps(preview.text, chip + width(preview.text), chrome, rowWidth)
        is TranslatePreview.Applied -> AiSmartbarLayout.translationWraps(
            preview.text,
            chip + width(preview.text) + width(undo),
            chrome + with(density) { AiSmartbarLayout.CHIP_CHROME_DP.dp.toPx() },
            rowWidth,
        )
    }
    last[0] = wraps
    return wraps
}
