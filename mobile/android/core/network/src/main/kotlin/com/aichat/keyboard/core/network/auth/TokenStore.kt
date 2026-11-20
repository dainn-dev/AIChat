package com.aichat.keyboard.core.network.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow

/** The session credentials. The refresh token is single-use: every refresh returns a new pair. */
@Serializable
data class AuthTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
) {
    // Never print tokens (logs, crash reports).
    override fun toString(): String = "AuthTokens(<redacted>)"
}

/**
 * Persistent session storage, shared by everything in the app process (settings UI and IME service).
 *
 * The methods are blocking and thread-safe: OkHttp's [okhttp3.Authenticator] calls them from its own worker
 * threads. Call them off the main thread.
 */
interface TokenStore {
    /** The current session, or null when signed out. Emits after every change. */
    val tokens: StateFlow<AuthTokens?>

    /** Reads the current session, loading it from disk on first use. */
    fun current(): AuthTokens?

    fun save(tokens: AuthTokens)

    fun clear()

    /**
     * Replaces the session with [new] (null signs out) only if it is still [expected]. Returns false and changes
     * nothing otherwise. Used by the refresh path so a stale refresh can never overwrite or wipe a newer login.
     */
    fun compareAndSet(expected: AuthTokens?, new: AuthTokens?): Boolean
}
