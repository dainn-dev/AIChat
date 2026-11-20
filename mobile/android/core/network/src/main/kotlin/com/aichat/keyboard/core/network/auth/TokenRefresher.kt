package com.aichat.keyboard.core.network.auth

import com.aichat.keyboard.core.network.model.RefreshRequest
import com.aichat.keyboard.core.network.model.RefreshResponse
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Calls `POST /auth/refresh` synchronously. It runs on OkHttp's thread inside [TokenAuthenticator], so it uses
 * a plain blocking call on a client that has no authenticator (a refresh must never trigger another refresh).
 */
class TokenRefresher(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val json: Json,
) {
    sealed interface Outcome {
        data class Refreshed(val tokens: AuthTokens) : Outcome

        /** The server rejected the refresh token (revoked, expired, unknown). The session is over. */
        data object Rejected : Outcome

        /** No usable answer (offline, timeout, 5xx). The session may still be valid; try again later. */
        data class Failed(val cause: Exception) : Outcome
    }

    fun refresh(refreshToken: String): Outcome {
        val body = json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(baseUrl.resolve(REFRESH_PATH)!!)
            .post(body)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val payload = json.decodeFromString(RefreshResponse.serializer(), response.body!!.string())
                        Outcome.Refreshed(AuthTokens(payload.accessToken, payload.refreshToken))
                    }
                    // 401: invalid/expired/revoked token. 400: the token failed validation (e.g. too long).
                    response.code == 401 || response.code == 400 -> Outcome.Rejected
                    else -> Outcome.Failed(IOException("Refresh failed with HTTP ${response.code}"))
                }
            }
        } catch (e: IOException) {
            Outcome.Failed(e)
        } catch (e: RuntimeException) {
            // A 2xx with a body that does not match the contract. Treat as transient rather than signing out.
            Outcome.Failed(e)
        }
    }

    companion object {
        const val REFRESH_PATH = "auth/refresh"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
