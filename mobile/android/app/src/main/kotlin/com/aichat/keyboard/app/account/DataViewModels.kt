package com.aichat.keyboard.app.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.AiChatClient
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.ConversationSummary
import com.aichat.keyboard.core.network.model.Memory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shared sign-out handling for the data screens. When the session ends (logout elsewhere, or a rejected refresh
 * from the IME), all in-flight requests are cancelled, the loaded data is dropped from memory, and [onSignedOut]
 * lets the subclass publish a state the screen reacts to by navigating back.
 */
abstract class SignedInViewModel(protected val client: AiChatClient) : ViewModel() {
    private val jobs = mutableSetOf<Job>()

    /**
     * Starts following the session. Subclasses call it at the end of their `init`, after their own state exists
     * ([onSignedOut] may run right away).
     */
    protected fun observeSession() {
        viewModelScope.launch {
            client.isSignedIn.collect { signedIn ->
                if (!signedIn) {
                    synchronized(jobs) { jobs.toList().also { jobs.clear() } }.forEach { it.cancel() }
                    onSignedOut()
                }
            }
        }
    }

    protected abstract fun onSignedOut()

    /** Launches a request that is cancelled on sign-out. */
    protected fun launchRequest(block: suspend () -> Unit) {
        val job = viewModelScope.launch { block() }
        synchronized(jobs) { jobs += job }
        job.invokeOnCompletion { synchronized(jobs) { jobs -= job } }
    }
}

/** "Saved conversations": paginated list of stored conversations (metadata only) with delete. */
class ConversationsViewModel(client: AiChatClient) : SignedInViewModel(client) {
    data class State(
        val items: List<ConversationSummary> = emptyList(),
        val total: Int = 0,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: ApiError? = null,
        val deletingIds: Set<String> = emptySet(),
        /** The session ended; the screen navigates back. */
        val signedOut: Boolean = false,
    ) {
        val canLoadMore: Boolean get() = loaded && items.size < total
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
        observeSession()
    }

    override fun onSignedOut() {
        _state.value = State(signedOut = true)
    }

    fun refresh() = load(offset = 0)

    fun loadMore() = load(offset = _state.value.items.size)

    private fun load(offset: Int) {
        if (_state.value.loading || _state.value.signedOut) return
        _state.update { it.copy(loading = true, error = null) }
        launchRequest {
            val result = client.conversations(offset = offset)
            _state.update {
                if (it.signedOut) return@update it
                when (result) {
                    is ApiResult.Success -> {
                        val page = result.value
                        val merged = if (offset == 0) page.conversations else it.items + page.conversations
                        it.copy(
                            items = merged.distinctBy { c -> c.id },
                            total = page.total,
                            loading = false,
                            loaded = true,
                        )
                    }
                    is ApiResult.Failure -> it.copy(loading = false, error = result.error)
                }
            }
        }
    }

    fun delete(id: String) {
        if (id in _state.value.deletingIds || _state.value.signedOut) return
        _state.update { it.copy(deletingIds = it.deletingIds + id, error = null) }
        launchRequest {
            val result = client.deleteConversation(id)
            _state.update {
                when {
                    it.signedOut -> it
                    result.isGone() -> it.copy(
                        items = it.items.filterNot { c -> c.id == id },
                        total = (it.total - 1).coerceAtLeast(0),
                        deletingIds = it.deletingIds - id,
                    )
                    else -> it.copy(deletingIds = it.deletingIds - id, error = (result as ApiResult.Failure).error)
                }
            }
        }
    }
}

/** "Memories": everything AIChat has remembered, with delete (hard delete server-side). */
class MemoriesViewModel(client: AiChatClient) : SignedInViewModel(client) {
    data class State(
        val items: List<Memory> = emptyList(),
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: ApiError? = null,
        val deletingIds: Set<String> = emptySet(),
        /** The session ended; the screen navigates back. */
        val signedOut: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
        observeSession()
    }

    override fun onSignedOut() {
        _state.value = State(signedOut = true)
    }

    fun refresh() {
        if (_state.value.loading || _state.value.signedOut) return
        _state.update { it.copy(loading = true, error = null) }
        launchRequest {
            val result = client.memories()
            _state.update {
                when {
                    it.signedOut -> it
                    result is ApiResult.Success -> it.copy(items = result.value, loading = false, loaded = true)
                    else -> it.copy(loading = false, error = (result as ApiResult.Failure).error)
                }
            }
        }
    }

    fun delete(id: String) {
        if (id in _state.value.deletingIds || _state.value.signedOut) return
        _state.update { it.copy(deletingIds = it.deletingIds + id, error = null) }
        launchRequest {
            val result = client.deleteMemory(id)
            _state.update {
                when {
                    it.signedOut -> it
                    result.isGone() ->
                        it.copy(items = it.items.filterNot { m -> m.id == id }, deletingIds = it.deletingIds - id)
                    else -> it.copy(deletingIds = it.deletingIds - id, error = (result as ApiResult.Failure).error)
                }
            }
        }
    }
}

/** Deleted now, or already gone (404): either way the item is no longer stored. */
private fun ApiResult<Unit>.isGone(): Boolean =
    this is ApiResult.Success || (this as? ApiResult.Failure)?.error is ApiError.NotFound
