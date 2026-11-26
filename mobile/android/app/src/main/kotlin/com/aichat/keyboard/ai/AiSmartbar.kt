/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aichat.keyboard.core.network.model.Tone
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.subtypeManager
import org.florisboard.lib.snygg.ui.SnyggChip
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggText

/** The chips shown when the AI button is open, after the "Suggest replies" chip, in display order. */
private val AiChips: List<Pair<AiAction, Int>> = listOf(
    AiAction.Rewrite(Tone.FLIRTY) to R.string.aichat__ai__rewrite_flirty,
    AiAction.Rewrite(Tone.FRIENDLY) to R.string.aichat__ai__rewrite_friendly,
    AiAction.Rewrite(Tone.PROFESSIONAL) to R.string.aichat__ai__rewrite_polite,
    AiAction.Rewrite(Tone.FUNNY) to R.string.aichat__ai__rewrite_funny,
    AiAction.Translate("en") to R.string.aichat__ai__translate_en,
    AiAction.Translate("vi") to R.string.aichat__ai__translate_vi,
)

/** Reply tones, flirty first (the main use case). */
private val ReplyTones: List<Pair<Tone, Int>> = listOf(
    Tone.FLIRTY to R.string.aichat__ai__tone_flirty,
    Tone.FUNNY to R.string.aichat__ai__tone_funny,
    Tone.FRIENDLY to R.string.aichat__ai__tone_friendly,
    Tone.PROFESSIONAL to R.string.aichat__ai__tone_polite,
    Tone.MATURE to R.string.aichat__ai__tone_mature,
)

private val ChipAttrs = mapOf("state" to "inactive")
private val ActiveChipAttrs = mapOf("state" to "active")

/**
 * Sparkle button in the bottom strip, next to the globe; opens and closes the AI chips, which replace the
 * smartbar suggestions. [onOpen] runs before opening (e.g. to leave the emoji panel, which has no smartbar).
 */
@Composable
fun AiButton(
    controller: AiAssistController,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
) {
    val state by controller.state.collectAsState()
    val open = state != AiAssistState.Closed
    val description = keyboardString(R.string.aichat__ai__button)
    SnyggIconButton(
        elementName = FlorisImeUi.SmartbarActionKey.elementName,
        onClick = {
            if (!open) onOpen()
            controller.toggle()
        },
        modifier = modifier.semantics { contentDescription = description },
    ) {
        SnyggIcon(
            elementName = FlorisImeUi.SmartbarActionKey.elementName,
            imageVector = if (open) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
            modifier = iconModifier,
        )
    }
}

/** Replaces the suggestion row while the AI button is open. */
@Composable
fun RowScope.AiSmartbarPanel(controller: AiAssistController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (val s = state) {
            AiAssistState.Closed -> Unit
            AiAssistState.Ready -> ActionChips(controller)
            AiAssistState.ChoosingReplyTone -> ToneChips(controller)
            is AiAssistState.Suggestions -> SuggestionChips(controller, s.replies)
            is AiAssistState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
                    strokeWidth = 2.dp,
                )
                Message(R.string.aichat__ai__loading)
            }
            is AiAssistState.Done -> {
                Message(R.string.aichat__ai__done)
                Chip(R.string.aichat__ai__undo, active = true) { controller.undo() }
                Chip(R.string.aichat__ai__ok) { controller.dismissMessage() }
            }
            is AiAssistState.Failed -> {
                Message(s.reason.messageRes())
                if (s.reason == AiFailure.NO_CONVERSATION && s.captureDisabled) {
                    Chip(R.string.aichat__ai__enable_reading, active = true) {
                        controller.reset()
                        openAccessibilitySettings(context)
                    }
                }
                if (s.reason == AiFailure.NOT_SIGNED_IN) {
                    Chip(R.string.aichat__ai__sign_in, active = true) {
                        controller.reset()
                        FlorisImeService.launchSettings()
                    }
                }
                Chip(R.string.aichat__ai__ok) { controller.dismissMessage() }
            }
        }
    }
}

@Composable
private fun ActionChips(controller: AiAssistController) {
    LazyRow(
        modifier = Modifier.fillMaxHeight(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Chip(R.string.aichat__ai__suggest_replies, active = true) { controller.startReply() } }
        items(AiChips) { (action, label) ->
            Chip(label) { controller.run(action) }
        }
    }
}

@Composable
private fun ToneChips(controller: AiAssistController) {
    LazyRow(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Chip(R.string.aichat__ai__back) { controller.dismissMessage() } }
        items(ReplyTones) { (tone, label) ->
            Chip(label) { controller.requestReplies(tone) }
        }
    }
}

@Composable
private fun SuggestionChips(controller: AiAssistController, replies: List<String>) {
    LazyRow(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Chip(R.string.aichat__ai__back) { controller.startReply() } }
        items(replies) { reply ->
            SnyggChip(
                elementName = FlorisImeUi.ClipboardFilterChip.elementName,
                attributes = ChipAttrs,
                onClick = { controller.pickSuggestion(reply) },
                text = reply,
            )
        }
    }
}

/** Opens the system accessibility settings, where the user can turn on AIChat's chat reader. */
private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun Chip(@StringRes label: Int, active: Boolean = false, onClick: () -> Unit) {
    SnyggChip(
        elementName = FlorisImeUi.ClipboardFilterChip.elementName,
        attributes = if (active) ActiveChipAttrs else ChipAttrs,
        onClick = onClick,
        text = keyboardString(label),
    )
}

@Composable
private fun RowScope.Message(@StringRes text: Int) {
    SnyggText(
        elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp),
        text = keyboardString(text),
    )
}

@StringRes
private fun AiFailure.messageRes(): Int = when (this) {
    AiFailure.UNAVAILABLE -> R.string.aichat__ai__error_unavailable
    AiFailure.EMPTY_TEXT -> R.string.aichat__ai__error_empty
    AiFailure.TOO_LONG -> R.string.aichat__ai__error_too_long
    AiFailure.NOT_SIGNED_IN -> R.string.aichat__ai__error_not_signed_in
    AiFailure.QUOTA -> R.string.aichat__ai__error_quota
    AiFailure.NETWORK -> R.string.aichat__ai__error_network
    AiFailure.TIMEOUT -> R.string.aichat__ai__error_timeout
    AiFailure.SERVER -> R.string.aichat__ai__error_server
    AiFailure.TEXT_CHANGED -> R.string.aichat__ai__error_text_changed
    AiFailure.NO_CONVERSATION -> R.string.aichat__ai__error_no_conversation
}

/**
 * AI strings follow the keyboard language picked with the globe key (Tiếng Việt / English), not the system
 * language: the chips belong to the keyboard, like its key labels.
 */
@Composable
private fun keyboardString(@StringRes id: Int): String {
    val context = LocalContext.current
    val subtypeManager by context.subtypeManager()
    val subtype by subtypeManager.activeSubtypeFlow.collectAsState()
    val locale = subtype.primaryLocale.base
    val resources = remember(locale) {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        context.createConfigurationContext(config).resources
    }
    return resources.getString(id)
}
