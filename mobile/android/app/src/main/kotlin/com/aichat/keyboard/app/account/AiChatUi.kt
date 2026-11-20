package com.aichat.keyboard.app.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.keyboard.core.network.error.ApiError
import dev.patrickgold.florisboard.R
import org.florisboard.lib.compose.stringRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Localized, user-facing text for an [ApiError]. Only our own strings are shown: never the server's message or an
 * exception text, which are English, may leak internals, and are kept in [ApiError.message] for logs only.
 */
@Composable
fun apiErrorMessage(error: ApiError): String = when (error) {
    is ApiError.Network -> stringRes(R.string.aichat__error__network)
    is ApiError.Unauthorized -> stringRes(R.string.aichat__error__unauthorized)
    is ApiError.QuotaExceeded -> if (error.limit != null) {
        stringRes(R.string.aichat__error__quota_with_limit, "limit" to error.limit)
    } else {
        stringRes(R.string.aichat__error__quota)
    }
    is ApiError.Upstream -> stringRes(R.string.aichat__error__upstream)
    is ApiError.Validation -> stringRes(R.string.aichat__error__validation)
    is ApiError.NotFound -> stringRes(R.string.aichat__error__not_found)
    is ApiError.Conflict, is ApiError.Unknown -> stringRes(R.string.aichat__error__unknown)
}

@Composable
fun AiChatErrorText(error: ApiError, modifier: Modifier = Modifier) {
    Text(
        text = apiErrorMessage(error),
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Error text with a retry button. */
@Composable
fun AiChatErrorWithRetry(error: ApiError, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AiChatErrorText(error)
        TextButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringRes(R.string.aichat__action__retry))
        }
    }
}

@Composable
fun AiChatLoading() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun AiChatInfoText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun AiChatConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes(R.string.aichat__action__cancel)) } },
    )
}

private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

/** Formats a backend ISO-8601 timestamp in the device's zone and locale. Falls back to the raw string. */
fun formatTimestamp(iso: String): String =
    runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(dateTimeFormatter) }.getOrDefault(iso)
