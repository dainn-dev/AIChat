/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.keyboard.app.account.AiChatInfoText
import com.aichat.keyboard.app.account.AiChatLoading
import com.aichat.keyboard.core.network.model.ChatMessage
import com.aichat.keyboard.core.network.model.MessageSender
import com.aichat.keyboard.core.network.model.Tone
import dev.patrickgold.florisboard.R
import org.florisboard.lib.compose.stringRes

/** What the screen can ask its host activity to do. */
interface ShareReplyActions {
    fun flipSender(index: Int)
    fun requestReplies(tone: Tone)
    fun retry()
    fun signIn()
    fun copy(text: String)
    fun close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareReplyScreen(state: ShareReplyViewModel.State, actions: ShareReplyActions) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringRes(R.string.aichat__share__title)) },
                navigationIcon = {
                    IconButton(onClick = actions::close) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringRes(R.string.aichat__share__close))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.reading) {
                ShareReplyViewModel.Reading.READING -> Column {
                    AiChatInfoText(stringRes(R.string.aichat__share__reading))
                    AiChatLoading()
                }
                ShareReplyViewModel.Reading.UNREADABLE -> Column {
                    AiChatInfoText(stringRes(R.string.aichat__share__unreadable))
                    TextButton(onClick = actions::close, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringRes(R.string.aichat__share__close))
                    }
                }
                ShareReplyViewModel.Reading.READY -> ReadyContent(state, actions)
            }
        }
    }
}

@Composable
private fun ReadyContent(state: ShareReplyViewModel.State, actions: ShareReplyActions) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { SectionTitle(stringRes(R.string.aichat__share__tone_title)) }
        item { ToneChips(selected = state.tone, onSelect = actions::requestReplies) }
        item { ReplySection(state.reply, actions) }
        item { SectionTitle(stringRes(R.string.aichat__share__conversation_title)) }
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                state.contactLabel?.let { name ->
                    Text(
                        text = stringRes(R.string.aichat__share__contact, "name" to name),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = stringRes(R.string.aichat__share__flip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(state.messages) { index, message ->
            MessageBubble(message, onClick = { actions.flipSender(index) })
        }
        item {
            Text(
                text = stringRes(R.string.aichat__share__privacy_note),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ToneChips(selected: Tone?, onSelect: (Tone) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShareReplyViewModel.TONES.forEach { tone ->
            FilterChip(
                selected = tone == selected,
                onClick = { onSelect(tone) },
                label = { Text(toneLabel(tone)) },
            )
        }
    }
}

@Composable
private fun toneLabel(tone: Tone): String = stringRes(
    when (tone) {
        Tone.FLIRTY -> R.string.aichat__share__tone_flirty
        Tone.FUNNY -> R.string.aichat__share__tone_funny
        Tone.FRIENDLY -> R.string.aichat__share__tone_friendly
        Tone.PROFESSIONAL -> R.string.aichat__share__tone_polite
        Tone.MATURE -> R.string.aichat__share__tone_mature
    },
)

@Composable
private fun ReplySection(reply: ShareReplyViewModel.ReplyState, actions: ShareReplyActions) {
    when (reply) {
        ShareReplyViewModel.ReplyState.Idle -> Unit
        is ShareReplyViewModel.ReplyState.Loading -> Column {
            AiChatInfoText(stringRes(R.string.aichat__share__loading))
            AiChatLoading()
        }
        is ShareReplyViewModel.ReplyState.Failed -> FailureContent(reply.failure, actions)
        is ShareReplyViewModel.ReplyState.Suggestions -> Column {
            SectionTitle(stringRes(R.string.aichat__share__suggestions_title))
            reply.texts.forEach { text -> SuggestionCard(text, onCopy = { actions.copy(text) }) }
        }
    }
}

@Composable
private fun FailureContent(failure: ShareFailure, actions: ShareReplyActions) {
    val message = stringRes(
        when (failure) {
            ShareFailure.NOT_SIGNED_IN -> R.string.aichat__share__error_not_signed_in
            ShareFailure.QUOTA -> R.string.aichat__share__error_quota
            ShareFailure.NETWORK -> R.string.aichat__share__error_network
            ShareFailure.TIMEOUT -> R.string.aichat__share__error_timeout
            ShareFailure.SERVER -> R.string.aichat__share__error_server
        },
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            when (failure) {
                ShareFailure.NOT_SIGNED_IN -> Button(onClick = actions::signIn) {
                    Text(stringRes(R.string.aichat__share__sign_in))
                }
                ShareFailure.QUOTA -> Unit
                else -> OutlinedButton(onClick = actions::retry) { Text(stringRes(R.string.aichat__share__retry)) }
            }
        }
    }
}

@Composable
private fun SuggestionCard(text: String, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = onCopy, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringRes(R.string.aichat__share__copy))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onClick: () -> Unit) {
    val isMe = message.sender == MessageSender.ME
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isMe) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.widthIn(max = 300.dp).clickable(onClick = onClick),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = stringRes(if (isMe) R.string.aichat__share__sender_me else R.string.aichat__share__sender_them),
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(text = message.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
