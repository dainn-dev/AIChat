/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.editorInstance
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.flow.drop
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton

/**
 * Target languages of live translation, as backend codes with their own names (a language is recognised by its
 * own name, so these are not translated).
 */
private val TranslateLanguages: List<Pair<String, String>> = listOf(
    "vi" to "Tiếng Việt",
    "en" to "English",
    "zh" to "中文",
    "ja" to "日本語",
    "ko" to "한국어",
    "th" to "ไทย",
    "fr" to "Français",
    "es" to "Español",
    "de" to "Deutsch",
)

internal fun languageName(code: String): String = TranslateLanguages.firstOrNull { it.first == code }?.second ?: code

private val ChipAttrs = mapOf("state" to "inactive")

/**
 * Translate button in the bottom strip, next to the clipboard key: opens and closes live translation. It opens
 * into English on the Vietnamese keyboard and into Vietnamese otherwise, until the user picks a language.
 * [onOpen] runs before opening (e.g. to leave the clipboard panel, which has no smartbar).
 */
@Composable
fun TranslateButton(
    controller: AiAssistController,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val state by controller.state.collectAsState()
    val open = state is AiAssistState.Translating
    val description = keyboardString(R.string.aichat__ai__translate_button)
    SnyggIconButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        onClick = {
            if (!open) onOpen()
            val keyboardIsVietnamese = subtypeManager.activeSubtype.primaryLocale.language == "vi"
            controller.toggleTranslate(
                // What the user types goes out in the other language; a copied message is read in theirs.
                defaultLang = if (keyboardIsVietnamese) "en" else "vi",
                copiedDefaultLang = if (keyboardIsVietnamese) "vi" else "en",
            )
        },
        modifier = modifier.semantics { contentDescription = description },
    ) {
        SnyggIcon(
            elementName = FlorisImeUi.SmartbarActionKey.elementName,
            imageVector = if (open) Icons.Filled.Translate else Icons.Outlined.Translate,
            modifier = iconModifier,
        )
    }
}

/**
 * Live translation in the smartbar: the language chip (tap to switch language), then the translation of the
 * field, refreshed when the user pauses typing. Tapping the translation puts it in the field.
 */
@Composable
internal fun RowScope.TranslatePanel(controller: AiAssistController, state: AiAssistState.Translating) {
    FollowFieldEdits(controller)
    val copied = state.copied
    if (state.choosingLang) {
        LanguageChips(controller, current = copied?.targetLang ?: state.targetLang, forCopied = copied != null)
        return
    }
    if (copied != null) {
        CopiedPanel(controller, copied)
        return
    }
    Chip("${languageName(state.targetLang)} ▾", active = true) { controller.toggleTranslateLangPicker() }
    when (val preview = state.preview) {
        // One line: the bar is one row tall while there is nothing to translate (AiSmartbarLayout.mainRowCount).
        TranslatePreview.Idle -> Message(R.string.aichat__ai__translate_hint, maxLines = 1)
        is TranslatePreview.Loading -> CircularProgressIndicator(
            modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
            strokeWidth = 2.dp,
        )
        is TranslatePreview.Ready -> TranslationCard(preview.text) { controller.applyTranslation() }
        is TranslatePreview.Applied -> {
            Message(preview.text, maxLines = AiSmartbarLayout.MESSAGE_MAX_LINES)
            Chip(R.string.aichat__ai__undo) { controller.undoTranslation() }
        }
        is TranslatePreview.Failed -> Message(preview.reason.messageRes(), maxLines = AiSmartbarLayout.MESSAGE_MAX_LINES)
    }
}

/** Every edit of the field (typing, sending, a cursor move) restarts the translation pause. */
@Composable
private fun FollowFieldEdits(controller: AiAssistController) {
    val context = LocalContext.current
    val editorInstance by context.editorInstance()
    LaunchedEffect(controller) {
        // The current value is the field as it was when the panel opened, which the controller already read.
        editorInstance.activeContentFlow.drop(1).collect { controller.onFieldEdited() }
    }
}

/**
 * The copied message's translation, read-only (it is the partner's message, not something to send). Closing it
 * goes back to live translation of what the user types.
 */
@Composable
private fun RowScope.CopiedPanel(controller: AiAssistController, copied: CopiedTranslation) {
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(FlorisImeSizing.smartbarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("${keyboardString(R.string.aichat__ai__translate_copied)} → ${languageName(copied.targetLang)} ▾", active = true) {
                controller.toggleTranslateLangPicker()
            }
            Spacer(modifier = Modifier.weight(1f))
            Chip(R.string.aichat__ai__translate_close) { controller.dismissCopied() }
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            when (val preview = copied.preview) {
                TranslatePreview.Idle -> Message(R.string.aichat__ai__translate_copied_hint)
                is TranslatePreview.Loading -> CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
                    strokeWidth = 2.dp,
                )
                is TranslatePreview.Ready -> Message(preview.text, maxLines = COPIED_MAX_LINES)
                is TranslatePreview.Applied -> Unit
                is TranslatePreview.Failed ->
                    Message(preview.reason.messageRes(), maxLines = AiSmartbarLayout.MESSAGE_MAX_LINES)
            }
        }
    }
}

/** Lines of a copied message's translation in the two lower rows of the tall bar. */
private const val COPIED_MAX_LINES = 3

/** [forCopied]: the chips pick the copied message's language, and the first chip drops the copied message. */
@Composable
private fun LanguageChips(controller: AiAssistController, current: String, forCopied: Boolean) {
    LazyRow(
        modifier = Modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            if (forCopied) {
                Chip(R.string.aichat__ai__translate_close) { controller.dismissCopied() }
            } else {
                Chip(R.string.aichat__ai__back) { controller.toggleTranslateLangPicker() }
            }
        }
        items(TranslateLanguages) { (code, name) ->
            Chip(name, active = code == current) { controller.setTranslateLang(code) }
        }
    }
}

@Composable
private fun RowScope.TranslationCard(text: String, onClick: () -> Unit) {
    val description = keyboardString(R.string.aichat__ai__translate_apply)
    SnyggBox(
        elementName = FlorisImeUi.ClipboardFilterChip.elementName,
        attributes = ChipAttrs,
        modifier = Modifier.weight(1f).fillMaxHeight(),
        // A click label, not a content description: screen readers must still read the translation itself.
        clickAndSemanticsModifier = Modifier.clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp),
            fontSize = AiSmartbarLayout.TEXT_SIZE_SP.sp,
            lineHeight = AiSmartbarLayout.LINE_HEIGHT_SP.sp,
            maxLines = AiSmartbarLayout.MESSAGE_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
