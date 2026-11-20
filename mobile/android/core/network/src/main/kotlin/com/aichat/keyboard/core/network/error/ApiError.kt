package com.aichat.keyboard.core.network.error

import java.io.IOException

/**
 * Typed failure of a backend call. Built from the backend error envelope
 * `{ "error": { "code", "message", "details"? } }` (see [ErrorEnvelopeMapper]) or from a transport failure.
 *
 * [message] is the server's English message. It is meant for logs; the UI maps the error type to its own
 * localized text.
 */
sealed interface ApiError {
    val message: String

    /** 401. The session is missing or expired and could not be refreshed, or the credentials are wrong. */
    data class Unauthorized(override val message: String) : ApiError

    /**
     * 429 `QUOTA_EXCEEDED` (daily count quota, [limit] from `details.limit`), or 402 `SPEND_CAP_EXCEEDED`
     * (Pro daily spend cap, [limit] is null). [metric] is e.g. `reply`, or `spend_cap` for the spend cap.
     */
    data class QuotaExceeded(
        override val message: String,
        val limit: Int?,
        val metric: String?,
    ) : ApiError

    /** 400 / 422. [fields] carries the class-validator messages from `details.fields`, when present. */
    data class Validation(
        override val message: String,
        val fields: List<String> = emptyList(),
    ) : ApiError

    /** 404, e.g. `DELETE /conversations/:id` for a missing or foreign conversation. */
    data class NotFound(override val message: String) : ApiError

    /** 409, e.g. signup with an email that is already registered. */
    data class Conflict(override val message: String) : ApiError

    /** 502/503/504. The AI provider or an upstream dependency failed; retrying later may help. */
    data class Upstream(override val message: String, val status: Int) : ApiError

    /** The request did not get an HTTP response (offline, DNS, TLS, timeout, connection reset). */
    data class Network(val cause: IOException) : ApiError {
        override val message: String get() = cause.message ?: cause.javaClass.simpleName
    }

    /** Anything else: other statuses, or a response body that does not match the contract. */
    data class Unknown(
        override val message: String,
        val status: Int?,
        val code: String?,
    ) : ApiError
}

/** Result of a backend call. Calls never throw for HTTP or transport failures; they return [Failure]. */
sealed interface ApiResult<out T> {
    data class Success<out T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(value))
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = (this as? ApiResult.Success)?.value

fun ApiResult<*>.errorOrNull(): ApiError? = (this as? ApiResult.Failure)?.error
