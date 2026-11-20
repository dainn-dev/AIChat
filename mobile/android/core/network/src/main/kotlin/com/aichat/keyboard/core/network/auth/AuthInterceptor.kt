package com.aichat.keyboard.core.network.auth

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

internal const val AUTHORIZATION_HEADER = "Authorization"
private const val BEARER_PREFIX = "Bearer "

internal fun Request.Builder.bearer(accessToken: String): Request.Builder =
    header(AUTHORIZATION_HEADER, BEARER_PREFIX + accessToken)

internal fun Request.bearerToken(): String? =
    header(AUTHORIZATION_HEADER)?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)

/**
 * Adds `Authorization: Bearer <access_token>` to every request of the authenticated client. It is only
 * installed on the client for protected endpoints; the auth endpoints use a separate client without it.
 */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val accessToken = tokenStore.current()?.accessToken
        val request = if (accessToken != null) {
            chain.request().newBuilder().bearer(accessToken).build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
