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
import com.aichat.keyboard.ai.AiSmartbarLayout
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
    val aiState by aiController.state.collectAsState()
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
    val aiState by aiController.state.collectAsState()
    return AiSmartbarLayout.mainRowCount(suggestionsOnly = layout == SmartbarLayout.SUGGESTIONS_ONLY, state = aiState)
}
