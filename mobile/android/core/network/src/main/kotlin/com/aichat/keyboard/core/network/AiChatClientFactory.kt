package com.aichat.keyboard.core.network

import com.aichat.keyboard.core.network.api.AiChatApi
import com.aichat.keyboard.core.network.api.AuthApi
import com.aichat.keyboard.core.network.auth.AuthInterceptor
import com.aichat.keyboard.core.network.auth.TokenAuthenticator
import com.aichat.keyboard.core.network.auth.TokenRefresher
import com.aichat.keyboard.core.network.auth.TokenStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Builds an [AiChatClient]. Pure JVM, so the unit tests use it against MockWebServer. */
object AiChatClientFactory {
    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_SECONDS = 45L

    /** The wire format: snake_case names come from @SerialName, null optional fields are left out. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun create(
        baseUrl: String,
        tokenStore: TokenStore,
        baseClient: OkHttpClient = defaultHttpClient(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): AiChatClient {
        val url = normalize(baseUrl)
        val converter = json.asConverterFactory("application/json; charset=utf-8".toMediaType())

        // Public client: no bearer token, no refresh. Used for the auth endpoints (including the refresh call itself).
        val publicClient = baseClient
        val refresher = TokenRefresher(publicClient, url, json)

        // Protected client: shares the connection pool and dispatcher with the public one.
        val authedClient = baseClient.newBuilder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore, refresher))
            .build()

        val authApi = Retrofit.Builder().baseUrl(url).client(publicClient)
            .addConverterFactory(converter).build().create(AuthApi::class.java)
        val api = Retrofit.Builder().baseUrl(url).client(authedClient)
            .addConverterFactory(converter).build().create(AiChatApi::class.java)

        return AiChatClient(authApi, api, tokenStore, ioDispatcher)
    }

    /**
     * No logging interceptor on purpose: request and response bodies carry tokens, passwords and third-party
     * chat text.
     *
     * Redirects are not followed: the API never redirects, and following one would replay the bearer token, the
     * password or the refresh token to wherever a compromised proxy or misconfigured host points.
     */
    fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Retrofit resolves relative paths against the base URL, which therefore must end with "/". */
    internal fun normalize(baseUrl: String): HttpUrl =
        (if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/").toHttpUrl()
}
