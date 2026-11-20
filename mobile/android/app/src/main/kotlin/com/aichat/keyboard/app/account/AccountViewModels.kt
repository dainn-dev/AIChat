package com.aichat.keyboard.app.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.AiChatClient
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.MeResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Sign-in / sign-up submission. The form fields themselves live in the composable (rememberSaveable). */
class AuthViewModel(private val client: AiChatClient) : ViewModel() {
    data class State(
        val isSignup: Boolean = false,
        val submitting: Boolean = false,
        val error: ApiError? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun toggleMode() = _state.update { State(isSignup = !it.isSignup) }

    fun clearError() = _state.update { it.copy(error = null) }

    fun submit(email: String, password: String, displayName: String?) {
        if (_state.value.submitting) return
        val isSignup = _state.value.isSignup
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = if (isSignup) {
                client.signup(email, password, displayName)
            } else {
                client.login(email, password)
            }
            // On success the session flow flips to signed-in and the screen swaps to the account details.
            _state.update { it.copy(submitting = false, error = (result as? ApiResult.Failure)?.error) }
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
        val loggingOut: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** null until the stored session has been read. */
    val isSignedIn: StateFlow<Boolean?> = client.isSignedIn
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            isSignedIn.collect { signedIn ->
                when (signedIn) {
                    true -> load()
                    false -> _state.value = State()
                    null -> Unit
                }
            }
        }
    }

    fun load() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = client.me()) {
                is ApiResult.Success -> _state.update { it.copy(loading = false, me = result.value) }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    fun setMemoryOptIn(enabled: Boolean) {
        val me = _state.value.me ?: return
        if (_state.value.savingOptIn) return
        _state.update { it.copy(savingOptIn = true, optInError = null) }
        viewModelScope.launch {
            when (val result = client.setMemoryOptIn(enabled)) {
                is ApiResult.Success -> _state.update {
                    it.copy(savingOptIn = false, me = (it.me ?: me).copy(settings = result.value))
                }
                is ApiResult.Failure -> _state.update { it.copy(savingOptIn = false, optInError = result.error) }
            }
        }
    }

    fun logout() {
        if (_state.value.loggingOut) return
        _state.update { it.copy(loggingOut = true) }
        viewModelScope.launch {
            client.logout()
            _state.update { it.copy(loggingOut = false) }
        }
    }
}
