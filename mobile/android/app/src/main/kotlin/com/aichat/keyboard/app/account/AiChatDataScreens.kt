package com.aichat.keyboard.app.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.keyboard.core.network.AiChat
import com.aichat.keyboard.core.network.model.ConversationSummary
import com.aichat.keyboard.core.network.model.Memory
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import org.florisboard.lib.compose.stringRes

// The screens render plain Columns: FlorisScreen already wraps its content in a vertical scroll container,
// so a LazyColumn cannot be nested here. Pages are 20 items with "Load more".

@Composable
fun AiChatConversationsScreen() = FlorisScreen {
    title = stringRes(R.string.aichat__conversations__title)
    previewFieldVisible = false

    val context = LocalContext.current
    val viewModel: ConversationsViewModel = viewModel { ConversationsViewModel(AiChat.client(context)) }
    val state by viewModel.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }

    content {
        state.error?.let { AiChatErrorWithRetry(it, viewModel::refresh) }
        when {
            !state.loaded && state.loading -> AiChatLoading()
            state.loaded && state.items.isEmpty() -> AiChatInfoText(stringRes(R.string.aichat__conversations__empty))
        }
        state.items.forEach { conversation ->
            DeletableRow(
                title = conversation.contactLabel ?: stringRes(R.string.aichat__conversations__unnamed),
                subtitle = stringRes(
                    R.string.aichat__conversations__item_details,
                    "platform" to conversation.platform,
                    "count" to conversation.messageCount,
                    "updated" to formatTimestamp(conversation.updatedAt),
                ),
                deleting = conversation.id in state.deletingIds,
                onDelete = { pendingDelete = conversation },
            )
        }
        if (state.canLoadMore) {
            TextButton(
                onClick = viewModel::loadMore,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                Text(stringRes(R.string.aichat__conversations__load_more))
            }
        }
    }

    pendingDelete?.let { conversation ->
        AiChatConfirmDialog(
            title = stringRes(R.string.aichat__conversations__delete_title),
            message = stringRes(R.string.aichat__conversations__delete_message),
            confirmLabel = stringRes(R.string.aichat__action__delete),
            onConfirm = { pendingDelete = null; viewModel.delete(conversation.id) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
fun AiChatMemoriesScreen() = FlorisScreen {
    title = stringRes(R.string.aichat__memories__title)
    previewFieldVisible = false

    val context = LocalContext.current
    val viewModel: MemoriesViewModel = viewModel { MemoriesViewModel(AiChat.client(context)) }
    val state by viewModel.state.collectAsState()
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }

    content {
        state.error?.let { AiChatErrorWithRetry(it, viewModel::refresh) }
        when {
            !state.loaded && state.loading -> AiChatLoading()
            state.loaded && state.items.isEmpty() -> AiChatInfoText(stringRes(R.string.aichat__memories__empty))
        }
        state.items.forEach { memory ->
            DeletableRow(
                title = memory.content,
                subtitle = listOf(
                    memory.contactLabel ?: stringRes(R.string.aichat__memories__general),
                    memoryKindLabel(memory.kind),
                    memoryStatusLabel(memory.status),
                ).joinToString(" · "),
                deleting = memory.id in state.deletingIds,
                onDelete = { pendingDelete = memory },
            )
        }
    }

    pendingDelete?.let { memory ->
        AiChatConfirmDialog(
            title = stringRes(R.string.aichat__memories__delete_title),
            message = stringRes(R.string.aichat__memories__delete_message),
            confirmLabel = stringRes(R.string.aichat__action__delete),
            onConfirm = { pendingDelete = null; viewModel.delete(memory.id) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun memoryKindLabel(kind: String): String = when (kind) {
    "interest" -> stringRes(R.string.aichat__memories__kind_interest)
    "job" -> stringRes(R.string.aichat__memories__kind_job)
    "birthday" -> stringRes(R.string.aichat__memories__kind_birthday)
    "fact" -> stringRes(R.string.aichat__memories__kind_fact)
    else -> kind
}

@Composable
private fun memoryStatusLabel(status: String): String = when (status) {
    "active" -> stringRes(R.string.aichat__memories__status_active)
    "pending_review" -> stringRes(R.string.aichat__memories__status_pending_review)
    "superseded" -> stringRes(R.string.aichat__memories__status_superseded)
    "dismissed" -> stringRes(R.string.aichat__memories__status_dismissed)
    else -> status
}

@Composable
private fun DeletableRow(title: String, subtitle: String, deleting: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (deleting) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringRes(R.string.aichat__action__delete))
            }
        }
    }
    HorizontalDivider()
}
