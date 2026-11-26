/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.schedule.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.keyboard.capture.ChatRecipient
import com.aichat.keyboard.capture.SupportedMessenger
import com.aichat.keyboard.schedule.ScheduleDatabase
import com.aichat.keyboard.schedule.ScheduleDraft
import com.aichat.keyboard.schedule.ScheduleStatus
import com.aichat.keyboard.schedule.ScheduledMessage
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.AppTheme
import dev.patrickgold.florisboard.app.apptheme.FlorisAppTheme
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.florisboard.lib.android.showShortToast
import org.florisboard.lib.compose.ProvideLocalizedResources
import org.florisboard.lib.compose.stringRes

/**
 * "Hẹn giờ gửi tin": a dialog-like popup opened from the keyboard's schedule key, where the user writes (or edits)
 * the message and picks the day and time. The keyboard cannot host a text field of its own (it is the input
 * method), hence an activity. Not exported.
 *
 * Saving stores the message on the device only ([ScheduleDatabase]); nothing goes to the backend.
 */
class ScheduleMessageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val recipient = recipientFrom(intent)
        val draft = intent.getStringExtra(EXTRA_TEXT).orEmpty().take(ScheduleDraft.MAX_CHARS)
        setContent {
            ProvideLocalizedResources(this, appName = R.string.aichat_app_name) {
                FlorisAppTheme(theme = AppTheme.AUTO) {
                    ScheduleMessageScreen(
                        recipient = recipient,
                        initialText = draft,
                        onSave = { text, sendAt -> save(recipient, text, sendAt) },
                        onClose = ::finish,
                    )
                }
            }
        }
    }

    private suspend fun save(recipient: ChatRecipient?, text: String, sendAtMs: Long) {
        recipient ?: return
        val now = System.currentTimeMillis()
        val message = ScheduledMessage(
            id = UUID.randomUUID().toString(),
            packageName = recipient.messenger.packageName,
            contactLabel = recipient.contactLabel,
            text = text.trim(),
            sendAtEpochMs = sendAtMs,
            status = ScheduleStatus.SCHEDULED,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        val saved = try {
            withContext(Dispatchers.IO) { ScheduleDatabase.get(applicationContext).messages().insert(message) }
            true
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Exception type only: the row holds the message text.
            Log.w(TAG, "Cannot save scheduled message: ${e.javaClass.simpleName}")
            false
        }
        if (saved) {
            applicationContext.showShortToast(
                R.string.aichat__schedule__saved,
                "time" to formatSendAt(applicationContext, sendAtMs),
            )
            finish()
        } else {
            applicationContext.showShortToast(R.string.aichat__schedule__save_failed)
        }
    }

    companion object {
        private const val TAG = "ScheduleMessage"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_CONTACT = "contact"

        /** Opens the popup from the keyboard (a service context, hence the new task). */
        fun launch(context: Context, text: String, recipient: ChatRecipient?) {
            val intent = Intent(context, ScheduleMessageActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TEXT, text)
            if (recipient != null) {
                intent.putExtra(EXTRA_PACKAGE, recipient.messenger.packageName)
                intent.putExtra(EXTRA_CONTACT, recipient.contactLabel)
            }
            context.startActivity(intent)
        }

        private fun recipientFrom(intent: Intent): ChatRecipient? {
            val messenger = SupportedMessenger.fromPackage(intent.getStringExtra(EXTRA_PACKAGE)) ?: return null
            val label = intent.getStringExtra(EXTRA_CONTACT)?.trim()?.ifEmpty { null } ?: return null
            return ChatRecipient(messenger, label)
        }
    }
}

private fun formatSendAt(context: Context, epochMs: Long): String {
    val time = DateFormat.getTimeFormat(context).format(Date(epochMs))
    val day = SimpleDateFormat("EEE dd/MM", context.resources.configuration.locales[0]).format(Date(epochMs))
    return "$time, $day"
}

private fun SupportedMessenger.displayName(): String = when (this) {
    SupportedMessenger.MESSENGER -> "Messenger"
    SupportedMessenger.ZALO -> "Zalo"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleMessageScreen(
    recipient: ChatRecipient?,
    initialText: String,
    onSave: suspend (text: String, sendAtMs: Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    var text by rememberSaveable { mutableStateOf(initialText) }
    var sendAt by rememberSaveable { mutableLongStateOf(ScheduleDraft.defaultSendAt(System.currentTimeMillis(), zone)) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val problem = ScheduleDraft.check(text, sendAt, System.currentTimeMillis(), recipient != null)

    // Scrim: a tap outside the card closes the popup, like a dialog.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                // Taps on the card must not reach the scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = stringRes(R.string.aichat__schedule__title), style = MaterialTheme.typography.titleLarge)

                if (recipient != null) {
                    Text(
                        text = stringRes(
                            R.string.aichat__schedule__to,
                            "name" to recipient.contactLabel,
                            "app" to recipient.messenger.displayName(),
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                } else {
                    Text(
                        text = stringRes(R.string.aichat__schedule__no_recipient),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (ScheduleDraft.accepts(it)) text = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text(stringRes(R.string.aichat__schedule__message_hint)) },
                    supportingText = { Text("${text.length}/${ScheduleDraft.MAX_CHARS}") },
                    minLines = 3,
                    maxLines = 8,
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = SimpleDateFormat("EEE dd/MM", context.resources.configuration.locales[0])
                                .format(Date(sendAt)),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    OutlinedButton(onClick = { pickingTime = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = DateFormat.getTimeFormat(context).format(Date(sendAt)),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                if (problem == ScheduleDraft.Problem.NOT_IN_FUTURE) {
                    Text(
                        text = stringRes(R.string.aichat__schedule__in_past),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) { Text(stringRes(R.string.aichat__schedule__cancel)) }
                    Button(
                        enabled = !saving && problem == null,
                        onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    onSave(text, sendAt)
                                } finally {
                                    saving = false
                                }
                            }
                        },
                    ) {
                        Text(stringRes(R.string.aichat__schedule__save))
                    }
                }
            }
        }
    }

    if (pickingDate) {
        val todayUtc = ScheduleDraft.dateUtcMs(System.currentTimeMillis(), zone)
        val state = rememberDatePickerState(
            initialSelectedDateMillis = ScheduleDraft.dateUtcMs(sendAt, zone),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { day ->
                        val local = java.time.Instant.ofEpochMilli(sendAt).atZone(zone)
                        sendAt = ScheduleDraft.sendAt(day, local.hour, local.minute, zone)
                    }
                    pickingDate = false
                }) { Text(stringRes(R.string.aichat__schedule__ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringRes(R.string.aichat__schedule__cancel)) }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (pickingTime) {
        val local = java.time.Instant.ofEpochMilli(sendAt).atZone(zone)
        val state = rememberTimePickerState(
            initialHour = local.hour,
            initialMinute = local.minute,
            is24Hour = DateFormat.is24HourFormat(context),
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    sendAt = ScheduleDraft.sendAt(ScheduleDraft.dateUtcMs(sendAt, zone), state.hour, state.minute, zone)
                    pickingTime = false
                }) { Text(stringRes(R.string.aichat__schedule__ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = false }) { Text(stringRes(R.string.aichat__schedule__cancel)) }
            },
            text = { TimePicker(state = state) },
        )
    }
}
