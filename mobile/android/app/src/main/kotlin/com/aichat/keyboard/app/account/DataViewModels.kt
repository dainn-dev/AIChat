package com.aichat.keyboard.app.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.keyboard.core.network.AiChatClient
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.model.ConversationSummary
import com.aichat.keyboard.core.network.model.Memory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** "Saved conversations": paginated list of stored conversations (metadata only) with delete. */
class ConversationsViewModel(private val client: AiChatClient) : ViewModel() {
    data class State(
        val items: List<ConversationSummary> = emptyList(),
        val total: Int = 0,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: ApiError? = null,
        val deletingIds: Set<String> = emptySet(),
    ) {
        val canLoadMore: Boolean get() = loaded && items.size < total
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = load(offset = 0)

    fun loadMore() = load(offset = _state.value.items.size)

    private fun load(offset: Int) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = client.conversations(offset = offset)) {
                is ApiResult.Success -> _state.update {
                    val page = result.value
                    val merged = if (offset == 0) page.conversations else it.items + page.conversations
                    it.copy(
                        items = merged.distinctBy { c -> c.id },
                        total = page.total,
                        loading = false,
                        loaded = true,
                    )
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    fun delete(id: String) {
        if (id in _state.value.deletingIds) return
        _state.update { it.copy(deletingIds = it.deletingIds + id, error = null) }
        viewModelScope.launch {
            val result = client.deleteConversation(id)
            val gone = result is ApiResult.Success ||
                (result as? ApiResult.Failure)?.error is ApiError.NotFound
            _state.update {
                if (gone) {
                    it.copy(
                        items = it.items.filterNot { c -> c.id == id },
                        total = (it.total - 1).coerceAtLeast(0),
                        deletingIds = it.deletingIds - id,
                    )
                } else {
                    it.copy(deletingIds = it.deletingIds - id, error = (result as ApiResult.Failure).error)
                }
            }
        }
    }
}

/** "Memories": everything AIChat has remembered, with delete (hard delete server-side). */
class MemoriesViewModel(private val client: AiChatClient) : ViewModel() {
    data class State(
        val items: List<Memory> = emptyList(),
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val error: ApiError? = null,
        val deletingIds: Set<String> = emptySet(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = client.memories()) {
                is ApiResult.Success -> _state.update {
                    it.copy(items = result.value, loading = false, loaded = true)
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    fun delete(id: String) {
        if (id in _state.value.deletingIds) return
        _state.update { it.copy(deletingIds = it.deletingIds + id, error = null) }
        viewModelScope.launch {
            val result = client.deleteMemory(id)
            val gone = result is ApiResult.Success ||
                (result as? ApiResult.Failure)?.error is ApiError.NotFound
            _state.update {
                if (gone) {
                    it.copy(items = it.items.filterNot { m -> m.id == id }, deletingIds = it.deletingIds - id)
                } else {
                    it.copy(deletingIds = it.deletingIds - id, error = (result as ApiResult.Failure).error)
                }
            }
        }
    }
}
