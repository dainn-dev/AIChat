package com.aichat.keyboard.app.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.AiChatClient
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.MeResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sign-in / sign-up form and submission.
 *
 * The form fields live here rather than in the composable so they survive rotation. They are never written to a
 * SavedStateHandle or Bundle: the password must not be persisted anywhere, so after process death the form is
 * simply empty.
 */
class AuthViewModel(private val client: AiChatClient) : ViewModel() {
    data class State(
        val isSignup: Boolean = false,
        val email: String = "",
        val password: String = "",
        val confirm: String = "",
        val displayName: String = "",
        val showPassword: Boolean = false,
        /** Field errors are shown only after the first submit attempt. */
        val attempted: Boolean = false,
        val submitting: Boolean = false,
        val error: ApiError? = null,
    ) {
        val validation: AuthValidation.Result
            get() = if (isSignup) {
                AuthValidation.validateSignup(email, password, confirm)
            } else {
                AuthValidation.validateLogin(email, password)
            }

        // Never print the password.
        override fun toString(): String = "AuthViewModel.State(isSignup=$isSignup, submitting=$submitting)"
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun toggleMode() = _state.update {
        it.copy(isSignup = !it.isSignup, confirm = "", attempted = false, error = null)
    }

    fun setEmail(value: String) =
        _state.update { it.copy(email = value.take(AuthValidation.EMAIL_MAX_LENGTH), error = null) }

    fun setPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun setConfirm(value: String) = _state.update { it.copy(confirm = value) }

    fun setDisplayName(value: String) =
        _state.update { it.copy(displayName = value.take(AuthValidation.DISPLAY_NAME_MAX_LENGTH)) }

    fun setShowPassword(show: Boolean) = _state.update { it.copy(showPassword = show) }

    fun submit() {
        val current = _state.value
        if (current.submitting) return
        if (!current.validation.isValid) {
            _state.update { it.copy(attempted = true) }
            return
        }
        _state.update { it.copy(attempted = true, submitting = true, error = null) }
        viewModelScope.launch {
            val result = if (current.isSignup) {
                client.signup(current.email, current.password, current.displayName)
            } else {
                client.login(current.email, current.password)
            }
            // On success the session flow flips to signed in and the screen swaps to the account details.
            _state.update {
                when (result) {
                    is ApiResult.Success -> State(isSignup = it.isSignup) // drop the password from memory
                    is ApiResult.Failure -> it.copy(submitting = false, error = result.error)
                }
            }
        }
    }
}

/** Signed-in account details, the memory opt-in and logout. */
class AccountViewModel(private val client: AiChatClient) : ViewModel() {
    data class State(
        val loading: Boolean = false,
        val me: MeResponse? = null,
        val error: ApiError? = null,
        val savingOptIn: Boolean = false,
        val optInError: ApiError? = null,
        /**
         * Set after turning memory off: what the backend deleted. Null counts mean the backend did not report them
         * (the deletion still happened). Cleared by the next opt-in change.
         */
        val optOutResult: OptOutResult? = null,
        val loggingOut: Boolean = false,
    )

    data class OptOutResult(val conversations: Int?, val memories: Int?)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** null until the stored session has been read. */
    val isSignedIn: StateFlow<Boolean?> = client.isSignedIn
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Every request whose result writes account state. Cancelled on sign-out so a late answer cannot land. */
    private var loadJob: Job? = null
    private var optInJob: Job? = null

    init {
        viewModelScope.launch {
            isSignedIn.collect { signedIn ->
                when (signedIn) {
                    true -> load()
                    false -> {
                        loadJob?.cancel()
                        optInJob?.cancel()
                        _state.value = State()
                    }
                    null -> Unit
                }
            }
        }
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            val result = client.me()
            // Also guard on the session, in case the sign-out was observed after the call already completed.
            if (isSignedIn.value != true) return@launch
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(loading = false, me = result.value)
                    is ApiResult.Failure -> it.copy(loading = false, error = result.error)
                }
            }
        }
    }

    fun setMemoryOptIn(enabled: Boolean) {
        val me = _state.value.me ?: return
        if (_state.value.savingOptIn) return
        _state.update { it.copy(savingOptIn = true, optInError = null, optOutResult = null) }
        optInJob = viewModelScope.launch {
            val result = client.setMemoryOptIn(enabled)
            if (isSignedIn.value != true) return@launch
            _state.update {
                when (result) {
                    is ApiResult.Success -> it.copy(
                        savingOptIn = false,
                        me = (it.me ?: me).copy(settings = result.value.settings),
                        optOutResult = if (enabled) {
                            null
                        } else {
                            OptOutResult(result.value.deleted?.conversations, result.value.deleted?.memories)
                        },
                    )
                    is ApiResult.Failure -> it.copy(savingOptIn = false, optInError = result.error)
                }
            }
        }
    }

    fun logout() {
        if (_state.value.loggingOut) return
        _state.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            // The local session is cleared even if this coroutine is cancelled (see AiChatClient.logout).
            client.logout()
            _state.update { it.copy(loggingOut = false) }
        }
    }
}
