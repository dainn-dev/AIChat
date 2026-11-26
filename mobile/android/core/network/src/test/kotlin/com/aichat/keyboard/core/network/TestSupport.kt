package com.aichat.keyboard.core.network

import com.aichat.keyboard.core.network.auth.AuthTokens
import com.aichat.keyboard.core.network.auth.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.atomic.AtomicInteger

/** Thread-safe in-memory [TokenStore] that counts writes. */
class InMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private val state = MutableStateFlow(initial)
    val writes = AtomicInteger(0)

    override val tokens: StateFlow<AuthTokens?> get() = state

    @Synchronized override fun current(): AuthTokens? = state.value

    @Synchronized override fun save(tokens: AuthTokens) {
        writes.incrementAndGet()
        state.value = tokens
    }

    @Synchronized override fun clear() {
        writes.incrementAndGet()
        state.value = null
    }

    @Synchronized override fun compareAndSet(expected: AuthTokens?, new: AuthTokens?): Boolean {
        if (state.value != expected) return false
        writes.incrementAndGet()
        state.value = new
        return true
    }
}

fun jsonResponse(code: Int, body: String): MockResponse =
    MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

fun errorResponse(code: Int, errorCode: String, message: String, details: String? = null): MockResponse {
    val detailsPart = details?.let { ""","details":$it""" } ?: ""
    return jsonResponse(code, """{"error":{"code":"$errorCode","message":"$message"$detailsPart}}""")
}

/** Payloads copied from backend/docs/keyboard-api.md and the backend DTOs. */
object Payloads {
    const val USER = """{"id":"4f7c2a9e-1b3d-4e5f-8a6b-7c8d9e0f1a2b","email":"linh@example.com","display_name":null,"tier":"free","created_at":"2026-11-01T08:00:00.000Z"}"""

    fun authTokens(access: String, refresh: String) =
        """{"user":$USER,"access_token":"$access","refresh_token":"$refresh"}"""

    fun refresh(access: String, refresh: String) = """{"access_token":"$access","refresh_token":"$refresh"}"""

    const val USAGE = """{ "replies_used": 1, "replies_limit": 20, "screenshots_used": 0, "screenshots_limit": 5 }"""

    const val ME = """{ "user": $USER, "tier": "free", "usage": $USAGE, "settings": { "memory_opt_in": false } }"""

    const val REPLY = """{
      "suggestions": [ { "tone": "Flirty", "text": "…" } ],
      "conversation_id": "7b0c4a3e-2f4e-4b8a-9a52-0d6f1f4a9c11",
      "usage": { "replies_used": 1, "replies_limit": 20, "screenshots_used": 0, "screenshots_limit": 5 }
    }"""

    fun replyWithStickerTags(tags: String) = """{
      "suggestions": [ { "tone": "Flirty", "text": "…" } ],
      "conversation_id": null,
      "usage": $USAGE,
      "sticker_tags": $tags
    }"""

    const val CONVERSATIONS = """{
      "conversations": [
        { "id": "0d6f1f4a-9c11-4b8a-9a52-7b0c4a3e2f4e", "platform": "zalo", "contact_label": "Linh", "source": "keyboard",
          "message_count": 12, "updated_at": "2026-11-20T09:15:00.000Z" }
      ],
      "total": 1, "limit": 20, "offset": 0
    }"""

    const val MEMORIES = """[
      { "id": "a1b2c3d4-0000-4000-8000-000000000001", "kind": "birthday", "content": "Linh's birthday is 12 March",
        "contact_label": "Linh", "status": "active", "confidence": 0.92, "source": "extracted",
        "source_ref": "0d6f1f4a-9c11-4b8a-9a52-7b0c4a3e2f4e", "created_at": "2026-11-20T09:16:00.000Z" },
      { "id": "a1b2c3d4-0000-4000-8000-000000000002", "kind": "fact", "content": "Prefers short replies",
        "contact_label": null, "status": "pending_review", "confidence": null, "source": "manual",
        "source_ref": null, "created_at": "2026-11-19T10:00:00.000Z" }
    ]"""
}
