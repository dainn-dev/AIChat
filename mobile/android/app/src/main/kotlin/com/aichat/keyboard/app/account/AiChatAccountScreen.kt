package com.aichat.keyboard.app.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aichat.keyboard.core.network.AiChat
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.model.MeResponse
import com.aichat.keyboard.core.network.model.Tier
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.stringRes

/**
 * One route for the whole account flow: the sign-in / sign-up form while signed out, the account details while
 * signed in. Following the session flow means a forced sign-out (rejected refresh, also from the IME) brings the
 * user straight back to the sign-in form.
 */
@Composable
fun AiChatAccountScreen() = FlorisScreen {
    val context = LocalContext.current
    val client = remember { AiChat.client(context) }
    val accountViewModel: AccountViewModel = viewModel { AccountViewModel(client) }
    val authViewModel: AuthViewModel = viewModel { AuthViewModel(client) }
    val signedIn by accountViewModel.isSignedIn.collectAsState()
    val authState by authViewModel.state.collectAsState()

    title = when {
        signedIn == true -> stringRes(R.string.aichat__account__title)
        authState.isSignup -> stringRes(R.string.aichat__auth__signup_title)
        else -> stringRes(R.string.aichat__auth__login_title)
    }
    previewFieldVisible = false

    content {
        when (signedIn) {
            null -> AiChatLoading()
            false -> AuthForm(authViewModel, authState)
            true -> AccountDetails(accountViewModel)
        }
    }
}

@Composable
private fun AuthForm(viewModel: AuthViewModel, state: AuthViewModel.State) {
    SecureWindowWhileShown()
    // Hide the password again whenever the app goes to the background (recents, someone else picking up the phone).
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.setShowPassword(false) }

    val validation = state.validation
    val showErrors = state.attempted
    val passwordTransformation =
        if (state.showPassword) VisualTransformation.None else PasswordVisualTransformation()

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::setEmail,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username + ContentType.EmailAddress },
            label = { Text(stringRes(R.string.aichat__auth__email)) },
            singleLine = true,
            enabled = !state.submitting,
            isError = showErrors && validation.email != null,
            supportingText = if (showErrors && validation.email != null) {
                { Text(stringRes(R.string.aichat__auth__error_email_invalid)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        if (state.isSignup) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::setDisplayName,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PersonFullName },
                label = { Text(stringRes(R.string.aichat__auth__display_name)) },
                singleLine = true,
                enabled = !state.submitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
        }
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            modifier = Modifier.fillMaxWidth().semantics {
                contentType = if (state.isSignup) ContentType.NewPassword else ContentType.Password
            },
            label = { Text(stringRes(R.string.aichat__auth__password)) },
            singleLine = true,
            enabled = !state.submitting,
            visualTransformation = passwordTransformation,
            trailingIcon = {
                IconButton(onClick = { viewModel.setShowPassword(!state.showPassword) }) {
                    Icon(
                        imageVector = if (state.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringRes(
                            if (state.showPassword) R.string.aichat__auth__hide_password else R.string.aichat__auth__show_password,
                        ),
                    )
                }
            },
            isError = showErrors && validation.password != null,
            supportingText = passwordErrorText(showErrors, validation.password),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (state.isSignup) ImeAction.Next else ImeAction.Done,
            ),
        )
        if (state.isSignup) {
            OutlinedTextField(
                value = state.confirm,
                onValueChange = viewModel::setConfirm,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.NewPassword },
                label = { Text(stringRes(R.string.aichat__auth__password_confirm)) },
                singleLine = true,
                enabled = !state.submitting,
                visualTransformation = passwordTransformation,
                isError = showErrors && validation.confirmMismatch,
                supportingText = if (showErrors && validation.confirmMismatch) {
                    { Text(stringRes(R.string.aichat__auth__error_password_mismatch)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
        }

        state.error?.let { error ->
            Text(
                text = authErrorMessage(error, state.isSignup),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(onClick = viewModel::submit, enabled = !state.submitting, modifier = Modifier.fillMaxWidth()) {
            if (state.submitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    stringRes(
                        if (state.isSignup) R.string.aichat__auth__signup_action else R.string.aichat__auth__login_action,
                    ),
                )
            }
        }
        TextButton(
            onClick = viewModel::toggleMode,
            enabled = !state.submitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringRes(if (state.isSignup) R.string.aichat__auth__to_login else R.string.aichat__auth__to_signup))
        }
    }
}

/** FLAG_SECURE while the credentials form is on screen: no screenshots, screen recording or recents thumbnail. */
@Composable
private fun SecureWindowWhileShown() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun passwordErrorText(
    attempted: Boolean,
    error: AuthValidation.PasswordError?,
): (@Composable () -> Unit)? {
    if (!attempted || error == null) return null
    val text = when (error) {
        AuthValidation.PasswordError.EMPTY -> stringRes(R.string.aichat__auth__error_password_empty)
        AuthValidation.PasswordError.TOO_SHORT -> stringRes(
            R.string.aichat__auth__error_password_short,
            "min" to AuthValidation.PASSWORD_MIN_LENGTH,
        )
        AuthValidation.PasswordError.TOO_LONG -> stringRes(
            R.string.aichat__auth__error_password_long,
            "max" to AuthValidation.PASSWORD_MAX_LENGTH,
        )
    }
    return { Text(text) }
}

@Composable
private fun authErrorMessage(error: ApiError, isSignup: Boolean): String = when {
    !isSignup && error is ApiError.Unauthorized -> stringRes(R.string.aichat__auth__error_wrong_credentials)
    // Deliberately generic: do not confirm that an account exists for this email.
    isSignup && error is ApiError.Conflict -> stringRes(R.string.aichat__auth__error_signup_failed)
    else -> apiErrorMessage(error)
}

@Composable
private fun AccountDetails(viewModel: AccountViewModel) {
    val navController = LocalNavController.current
    val state by viewModel.state.collectAsState()
    var confirmOptIn by remember { mutableStateOf(false) }
    var confirmOptOut by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val me = state.me
    when {
        me != null -> {
            AccountSummary(me)
            TextButton(onClick = viewModel::load, enabled = !state.loading, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringRes(R.string.aichat__account__reload))
            }
            state.error?.let { AiChatErrorText(it) }
        }
        state.error != null -> AiChatErrorWithRetry(state.error!!, viewModel::load)
        else -> AiChatLoading()
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    MemoryOptInRow(
        checked = me?.settings?.memoryOptIn ?: false,
        enabled = me != null && !state.savingOptIn,
        // Both directions need confirmation: turning it off deletes the keyboard-captured data server-side.
        onCheckedChange = { enabled -> if (enabled) confirmOptIn = true else confirmOptOut = true },
    )
    state.optInError?.let { AiChatErrorText(it) }
    state.optOutResult?.let { result ->
        AiChatInfoText(
            if (result.conversations != null && result.memories != null) {
                stringRes(
                    R.string.aichat__memory__opt_out_done_counts,
                    "conversations" to result.conversations,
                    "memories" to result.memories,
                )
            } else {
                stringRes(R.string.aichat__memory__opt_out_done)
            },
        )
    }

    Preference(
        icon = Icons.AutoMirrored.Outlined.Chat,
        title = stringRes(R.string.aichat__conversations__title),
        summary = stringRes(R.string.aichat__conversations__summary),
        onClick = { navController.navigate(AiChatRoutes.Conversations) },
    )
    Preference(
        icon = Icons.Outlined.Lightbulb,
        title = stringRes(R.string.aichat__memories__title),
        summary = stringRes(R.string.aichat__memories__summary),
        onClick = { navController.navigate(AiChatRoutes.Memories) },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    OutlinedButton(
        onClick = { confirmLogout = true },
        enabled = !state.loggingOut,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(stringRes(R.string.aichat__account__logout))
    }

    if (confirmOptIn) {
        AiChatConfirmDialog(
            title = stringRes(R.string.aichat__memory__opt_in_confirm_title),
            message = stringRes(R.string.aichat__memory__opt_in_disclosure),
            confirmLabel = stringRes(R.string.aichat__memory__opt_in_confirm_action),
            onConfirm = { confirmOptIn = false; viewModel.setMemoryOptIn(true) },
            onDismiss = { confirmOptIn = false },
        )
    }
    if (confirmOptOut) {
        // Cancel keeps memory on: the switch only reflects the server state, which is unchanged.
        AiChatConfirmDialog(
            title = stringRes(R.string.aichat__memory__opt_out_confirm_title),
            message = stringRes(R.string.aichat__memory__opt_out_confirm_message),
            confirmLabel = stringRes(R.string.aichat__memory__opt_out_confirm_action),
            onConfirm = { confirmOptOut = false; viewModel.setMemoryOptIn(false) },
            onDismiss = { confirmOptOut = false },
        )
    }
    if (confirmLogout) {
        AiChatConfirmDialog(
            title = stringRes(R.string.aichat__account__logout_confirm_title),
            message = stringRes(R.string.aichat__account__logout_confirm_message),
            confirmLabel = stringRes(R.string.aichat__account__logout),
            onConfirm = { confirmLogout = false; viewModel.logout() },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun AccountSummary(me: MeResponse) {
    val usage = me.usage
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabeledValue(stringRes(R.string.aichat__account__email), me.user.email)
        LabeledValue(
            stringRes(R.string.aichat__account__tier),
            stringRes(if (me.tier == Tier.PRO) R.string.aichat__account__tier_pro else R.string.aichat__account__tier_free),
        )
        Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(stringRes(R.string.aichat__account__usage_title), style = MaterialTheme.typography.titleSmall)
        Text(
            if (usage.repliesLimit != null) {
                stringRes(R.string.aichat__account__usage_replies, "used" to usage.repliesUsed, "limit" to usage.repliesLimit)
            } else {
                stringRes(R.string.aichat__account__usage_replies_unlimited, "used" to usage.repliesUsed)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (usage.screenshotsLimit != null) {
                stringRes(
                    R.string.aichat__account__usage_screenshots,
                    "used" to usage.screenshotsUsed,
                    "limit" to usage.screenshotsLimit,
                )
            } else {
                stringRes(R.string.aichat__account__usage_screenshots_unlimited, "used" to usage.screenshotsUsed)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MemoryOptInRow(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringRes(R.string.aichat__memory__opt_in_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        Text(
            text = stringRes(R.string.aichat__memory__opt_in_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
