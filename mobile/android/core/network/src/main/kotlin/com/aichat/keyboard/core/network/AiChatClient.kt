package com.aichat.keyboard.core.network

import com.aichat.keyboard.core.network.api.AiChatApi
import com.aichat.keyboard.core.network.api.AuthApi
import com.aichat.keyboard.core.network.auth.AuthTokens
import com.aichat.keyboard.core.network.auth.TokenStore
import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ApiResult
import com.aichat.keyboard.core.network.error.ErrorEnvelopeMapper
import com.aichat.keyboard.core.network.model.AuthTokensResponse
import com.aichat.keyboard.core.network.model.ConversationListResponse
import com.aichat.keyboard.core.network.model.KeyboardReplyRequest
import com.aichat.keyboard.core.network.model.KeyboardReplyResponse
import com.aichat.keyboard.core.network.model.KeyboardRewriteRequest
import com.aichat.keyboard.core.network.model.KeyboardTextResponse
import com.aichat.keyboard.core.network.model.KeyboardTranslateRequest
import com.aichat.keyboard.core.network.model.LoginRequest
import com.aichat.keyboard.core.network.model.LogoutRequest
import com.aichat.keyboard.core.network.model.MeResponse
import com.aichat.keyboard.core.network.model.Memory
import com.aichat.keyboard.core.network.model.PublicUser
import com.aichat.keyboard.core.network.model.SignupRequest
import com.aichat.keyboard.core.network.model.UpdateSettingsRequest
import com.aichat.keyboard.core.network.model.UserSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * The AIChat backend, for the settings UI and (in later phases) the IME. Every call returns [ApiResult] and
 * never throws for HTTP or transport failures. Create it with [AiChatClientFactory]; on Android use
 * `AiChat.client(context)` to get the per-process instance that the settings activity and the IME share.
 */
class AiChatClient internal constructor(
    private val authApi: AuthApi,
    private val api: AiChatApi,
    private val tokenStore: TokenStore,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** Whether a session is stored. Turns false on logout and when a refresh is rejected (forced sign-out). */
    val isSignedIn: Flow<Boolean> = tokenStore.tokens
        .onStart { withContext(ioDispatcher) { tokenStore.current() } }
        .map { it != null }
        .distinctUntilChanged()

    // -- Auth -----------------------------------------------------------------------------------------------

    suspend fun signup(email: String, password: String, displayName: String? = null): ApiResult<PublicUser> =
        authenticate { authApi.signup(SignupRequest(email.trim(), password, displayName?.trim()?.ifEmpty { null })) }

    suspend fun login(email: String, password: String): ApiResult<PublicUser> =
        authenticate { authApi.login(LoginRequest(email.trim(), password)) }

    /**
     * Revokes the refresh token server-side (best effort) and always clears the local session, even when
     * offline. A token that could not be revoked expires on its own (~30 days).
     */
    suspend fun logout(): ApiResult<Unit> = withContext(ioDispatcher) {
        val tokens = tokenStore.current() ?: return@withContext ApiResult.Success(Unit)
        // Best effort: the result is ignored on purpose, the user is signed out locally either way.
        safeUnitCall { authApi.logout(LogoutRequest(tokens.refreshToken)) }
        try {
            tokenStore.clear()
            ApiResult.Success(Unit)
        } catch (e: IOException) {
            ApiResult.Failure(ApiError.Unknown("Could not clear the session: ${e.message}", null, null))
        }
    }

    // -- Me -------------------------------------------------------------------------------------------------

    suspend fun me(): ApiResult<MeResponse> = authed { api.me() }

    suspend fun setMemoryOptIn(enabled: Boolean): ApiResult<UserSettings> =
        authed { api.updateSettings(UpdateSettingsRequest(enabled)).settings }

    // -- Conversations and memories ---------------------------------------------------------------------------

    suspend fun conversations(limit: Int = DEFAULT_PAGE_SIZE, offset: Int = 0): ApiResult<ConversationListResponse> =
        authed { api.conversations(limit.coerceIn(1, MAX_PAGE_SIZE), offset.coerceAtLeast(0)) }

    suspend fun deleteConversation(id: String): ApiResult<Unit> = authedUnit { api.deleteConversation(id) }

    suspend fun memories(status: String? = null): ApiResult<List<Memory>> = authed { api.memories(status = status) }

    suspend fun deleteMemory(id: String): ApiResult<Unit> = authedUnit { api.deleteMemory(id) }

    // -- Keyboard (used by the IME in later phases) -----------------------------------------------------------

    suspend fun reply(request: KeyboardReplyRequest): ApiResult<KeyboardReplyResponse> = authed { api.reply(request) }

    suspend fun rewrite(request: KeyboardRewriteRequest): ApiResult<KeyboardTextResponse> =
        authed { api.rewrite(request) }

    suspend fun translate(request: KeyboardTranslateRequest): ApiResult<KeyboardTextResponse> =
        authed { api.translate(request) }

    // -- Plumbing ---------------------------------------------------------------------------------------------

    private suspend fun authenticate(call: suspend () -> AuthTokensResponse): ApiResult<PublicUser> =
        withContext(ioDispatcher) {
            when (val result = safeCall(call)) {
                is ApiResult.Failure -> result
                is ApiResult.Success -> try {
                    tokenStore.save(AuthTokens(result.value.accessToken, result.value.refreshToken))
                    ApiResult.Success(result.value.user)
                } catch (e: IOException) {
                    // Signed in server-side, but we cannot persist the session. Report it as a failure.
                    ApiResult.Failure(ApiError.Unknown("Could not store the session: ${e.message}", null, null))
                }
            }
        }

    /** Protected call. Without a session it fails fast with [ApiError.Unauthorized] and sends nothing. */
    private suspend fun <T> authed(call: suspend () -> T): ApiResult<T> = withContext(ioDispatcher) {
        if (tokenStore.current() == null) {
            ApiResult.Failure(ApiError.Unauthorized(NOT_SIGNED_IN))
        } else {
            safeCall(call)
        }
    }

    private suspend fun authedUnit(call: suspend () -> Response<Unit>): ApiResult<Unit> =
        withContext(ioDispatcher) {
            if (tokenStore.current() == null) {
                ApiResult.Failure(ApiError.Unauthorized(NOT_SIGNED_IN))
            } else {
                safeUnitCall(call)
            }
        }

    private suspend fun <T> safeCall(call: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(call())
    } catch (e: HttpException) {
        ApiResult.Failure(ErrorEnvelopeMapper.fromHttp(e.code(), e.response()?.errorBody()?.readText()))
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive() // a cancelled coroutine surfaces as IOException("Canceled")
        ApiResult.Failure(ApiError.Network(e))
    } catch (e: SerializationException) {
        ApiResult.Failure(ApiError.Unknown("Unexpected response: ${e.message}", null, null))
    }

    private suspend fun safeUnitCall(call: suspend () -> Response<Unit>): ApiResult<Unit> =
        when (val result = safeCall(call)) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val response = result.value
                if (response.isSuccessful) {
                    ApiResult.Success(Unit)
                } else {
                    ApiResult.Failure(ErrorEnvelopeMapper.fromHttp(response.code(), response.errorBody()?.readText()))
                }
            }
        }

    private fun okhttp3.ResponseBody.readText(): String? = runCatching { use { it.string() } }.getOrNull()

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        private const val NOT_SIGNED_IN = "Not signed in"
    }
}
