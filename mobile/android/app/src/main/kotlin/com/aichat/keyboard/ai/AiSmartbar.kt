/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.ai

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aichat.keyboard.capture.ChatCaptureLauncher
import com.aichat.keyboard.core.network.model.Tone
import com.aichat.keyboard.sticker.StickerTag
import com.aichat.keyboard.sticker.suggest.AiReplyStickerSuggestions
import dev.patrickgold.florisboard.FlorisImeService
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.subtypeManager
import org.florisboard.lib.snygg.ui.SnyggBox
import org.florisboard.lib.snygg.ui.SnyggIcon
import org.florisboard.lib.snygg.ui.SnyggIconButton
import org.florisboard.lib.snygg.ui.SnyggRow

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
    // Live translation has its own button (TranslateButton); the sparkle stays outlined while it is open.
    val open = state != AiAssistState.Closed && state !is AiAssistState.Translating
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
            is AiAssistState.Translating -> TranslatePanel(controller, s)
            is AiAssistState.Suggestions -> SuggestionPanel(controller, s.replies, s.stickerTags)
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
                Message(s.messageRes(), maxLines = AiSmartbarLayout.MESSAGE_MAX_LINES)
                val readerChip = when {
                    s.reason == AiFailure.NO_CONVERSATION && s.captureDisabled -> R.string.aichat__ai__enable_reading
                    s.reason == AiFailure.CAPTURE_NOT_CONNECTED -> R.string.aichat__ai__open_reading
                    else -> null
                }
                if (readerChip != null) {
                    Chip(readerChip, active = true) {
                        controller.reset()
                        // Disclosure and consent first (Play policy); that screen opens the system settings.
                        ChatCaptureLauncher.openConsent()
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

/**
 * Reply suggestions, shown in the taller smartbar ([AiSmartbarLayout.SUGGESTION_ROWS] rows): one row of chips
 * (back, and the tones to ask again in another tone) with up to three matching stickers at its end, then the
 * replies as multi-line cards side by side.
 */
@Composable
private fun RowScope.SuggestionPanel(controller: AiAssistController, replies: List<String>, stickerTags: List<StickerTag>) {
    val rowHeight = FlorisImeSizing.smartbarHeight
    val cardAreaHeight = rowHeight * (AiSmartbarLayout.SUGGESTION_ROWS - 1)
    val maxLines = AiSmartbarLayout.cardMaxLines(cardAreaHeight.value / LocalDensity.current.fontScale)
    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Row(modifier = Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { Chip(R.string.aichat__ai__back) { controller.startReply() } }
                items(ReplyTones) { (tone, label) ->
                    Chip(label) { controller.requestReplies(tone) }
                }
            }
            AiReplyStickerSuggestions(tags = stickerTags, height = rowHeight)
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(cardAreaHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(replies) { reply ->
                SnyggBox(
                    elementName = FlorisImeUi.ClipboardFilterChip.elementName,
                    attributes = ChipAttrs,
                    modifier = Modifier
                        .fillParentMaxWidth(if (replies.size > 1) CARD_WIDTH_FRACTION else 1f)
                        .fillMaxHeight(),
                    clickAndSemanticsModifier = Modifier.clickable { controller.pickSuggestion(reply) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = reply,
                        modifier = Modifier.padding(horizontal = 6.dp),
                        fontSize = AiSmartbarLayout.TEXT_SIZE_SP.sp,
                        lineHeight = AiSmartbarLayout.LINE_HEIGHT_SP.sp,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Cards narrower than the bar, so the next one peeks in and the row reads as scrollable. */
private const val CARD_WIDTH_FRACTION = 0.8f

/** A one-line chip at [AiSmartbarLayout.TEXT_SIZE_SP] (the smartbar's ~17sp made the chips overflow). */
@Composable
internal fun Chip(@StringRes label: Int, active: Boolean = false, onClick: () -> Unit) {
    Chip(keyboardString(label), active, onClick)
}

@Composable
internal fun Chip(label: String, active: Boolean = false, onClick: () -> Unit) {
    SnyggRow(
        elementName = FlorisImeUi.ClipboardFilterChip.elementName,
        attributes = if (active) ActiveChipAttrs else ChipAttrs,
        clickAndSemanticsModifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 4.dp),
            fontSize = AiSmartbarLayout.TEXT_SIZE_SP.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A status or failure message; failures may wrap to [maxLines] lines (the bar grows for them) instead of "…". */
@Composable
internal fun RowScope.Message(@StringRes text: Int, maxLines: Int = 1) {
    Message(keyboardString(text), maxLines)
}

@Composable
internal fun RowScope.Message(text: String, maxLines: Int = 1) {
    SnyggBox(
        elementName = FlorisImeUi.SmartbarCandidateWord.elementName,
        modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            fontSize = AiSmartbarLayout.TEXT_SIZE_SP.sp,
            lineHeight = AiSmartbarLayout.LINE_HEIGHT_SP.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** With the chat reader off, the generic message offers to turn it on or copy; otherwise the chat was unreadable. */
@StringRes
private fun AiAssistState.Failed.messageRes(): Int =
    if (reason == AiFailure.NO_CONVERSATION && !captureDisabled) {
        R.string.aichat__ai__error_chat_unreadable
    } else {
        reason.messageRes()
    }

@StringRes
internal fun AiFailure.messageRes(): Int = when (this) {
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
    AiFailure.CAPTURE_NOT_CONNECTED -> R.string.aichat__ai__error_reader_not_connected
    AiFailure.UNSUPPORTED_CHAT_APP -> R.string.aichat__ai__error_unsupported_app
}

/**
 * AI strings follow the keyboard language picked with the globe key (Tiếng Việt / English), not the system
 * language: the chips belong to the keyboard, like its key labels.
 */
@Composable
internal fun keyboardString(@StringRes id: Int): String {
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
