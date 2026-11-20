package com.aichat.keyboard.core.network.auth

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Refresh-on-401 for the authenticated client.
 *
 * - **Single flight.** Refreshes are serialized by [refreshLock]. When several requests fail with the same
 *   expired access token, the first one refreshes; the others wait on the lock, see that the stored token has
 *   already changed, and retry with the new one. The refresh token is single-use (the backend rotates it), so
 *   two parallel refreshes would race and the loser would sign the user out. This prevents that.
 * - **Once per request.** A request that already went through a refresh and still gets 401 is not retried
 *   again, so there is no refresh loop.
 * - **Rotation is persisted.** The new pair from `/auth/refresh` replaces the stored session before any retry.
 * - **Sign-out on rejection.** If the server rejects the refresh token, the local session is cleared. A
 *   transient failure (offline, 5xx) keeps the session so the next call can try again.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refresher: TokenRefresher,
) : Authenticator {
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Only requests that carried our bearer token are eligible. Anything else is a genuine 401.
        val failedAccessToken = response.request.bearerToken() ?: return null
        if (response.priorResponseCount() >= MAX_ATTEMPTS) return null

        synchronized(refreshLock) {
            val current = tokenStore.current() ?: return null

            if (current.accessToken != failedAccessToken) {
                // Another request refreshed (or the user logged in again) while this one was in flight.
                return response.request.newBuilder().bearer(current.accessToken).build()
            }

            return when (val outcome = refresher.refresh(current.refreshToken)) {
                is TokenRefresher.Outcome.Refreshed -> {
                    if (!tokenStore.compareAndSet(current, outcome.tokens)) {
                        // The session changed under us (logout/login on another thread). Do not resurrect it.
                        return null
                    }
                    response.request.newBuilder().bearer(outcome.tokens.accessToken).build()
                }
                TokenRefresher.Outcome.Rejected -> {
                    tokenStore.compareAndSet(current, null)
                    null
                }
                is TokenRefresher.Outcome.Failed -> null
            }
        }
    }

    private fun Response.priorResponseCount(): Int {
        var count = 0
        var prior = priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        /** The original request plus one retry after a refresh. */
        const val MAX_ATTEMPTS = 1
    }
}
