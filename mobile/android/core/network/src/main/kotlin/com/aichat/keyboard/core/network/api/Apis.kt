package com.aichat.keyboard.core.network.api

import com.aichat.keyboard.core.network.model.AuthTokensResponse
import com.aichat.keyboard.core.network.model.ConversationListResponse
import com.aichat.keyboard.core.network.model.KeyboardCorrectRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyResponse
import com.aichat.keyboard.core.network.model.KeyboardRewriteRequest
import com.aichat.keyboard.core.network.model.KeyboardSummarizeRequest
import com.aichat.keyboard.core.network.model.KeyboardSummaryResponse
import com.aichat.keyboard.core.network.model.KeyboardTextResponse
import com.aichat.keyboard.core.network.model.KeyboardTranslateRequest
import com.aichat.keyboard.core.network.model.KeyboardWriteRequest
import com.aichat.keyboard.core.network.model.LoginRequest
import com.aichat.keyboard.core.network.model.LogoutRequest
import com.aichat.keyboard.core.network.model.MeResponse
import com.aichat.keyboard.core.network.model.Memory
import com.aichat.keyboard.core.network.model.SettingsResponse
import com.aichat.keyboard.core.network.model.SignupRequest
import com.aichat.keyboard.core.network.model.UpdateSettingsRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Public auth endpoints (`backend/src/auth/auth.controller.ts`). Served by a client WITHOUT the bearer
 * interceptor and refresh authenticator: a 401 from `/auth/login` means wrong credentials, not an expired session.
 * `/auth/refresh` is called by [com.aichat.keyboard.core.network.auth.TokenRefresher].
 */
internal interface AuthApi {
    /** 201. 409 when the email is taken. */
    @POST("auth/signup")
    suspend fun signup(@Body body: SignupRequest): AuthTokensResponse

    /** 200. 401 for wrong credentials. */
    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): AuthTokensResponse

    /** 204, idempotent. Revokes the refresh token. */
    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest): Response<Unit>
}

/** Protected endpoints. Served by the client with the bearer interceptor and the refresh authenticator. */
internal interface AiChatApi {
    @GET("me")
    suspend fun me(): MeResponse

    @PATCH("me/settings")
    suspend fun updateSettings(@Body body: UpdateSettingsRequest): SettingsResponse

    /** Newest first, metadata only. `limit` 1-100 (default 20), `offset` >= 0. */
    @GET("conversations")
    suspend fun conversations(
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
    ): ConversationListResponse

    /** 204. 404 when missing or not owned. Hard-deletes messages, linked screenshots and extracted memories. */
    @DELETE("conversations/{id}")
    suspend fun deleteConversation(@Path("id") id: String): Response<Unit>

    /** All filters optional; a null query parameter is omitted by Retrofit. */
    @GET("memories")
    suspend fun memories(
        @Query("kind") kind: String? = null,
        @Query("contact_label") contactLabel: String? = null,
        @Query("status") status: String? = null,
    ): List<Memory>

    /** 204. Hard delete (right to erasure). */
    @DELETE("memories/{id}")
    suspend fun deleteMemory(@Path("id") id: String): Response<Unit>

    @POST("v1/keyboard/reply")
    suspend fun reply(@Body body: KeyboardReplyRequest): KeyboardReplyResponse

    @POST("v1/keyboard/write")
    suspend fun write(@Body body: KeyboardWriteRequest): KeyboardTextResponse

    @POST("v1/keyboard/correct")
    suspend fun correct(@Body body: KeyboardCorrectRequest): KeyboardTextResponse

    @POST("v1/keyboard/summarize")
    suspend fun summarize(@Body body: KeyboardSummarizeRequest): KeyboardSummaryResponse

    @POST("v1/keyboard/rewrite")
    suspend fun rewrite(@Body body: KeyboardRewriteRequest): KeyboardTextResponse

    @POST("v1/keyboard/translate")
    suspend fun translate(@Body body: KeyboardTranslateRequest): KeyboardTextResponse
}
