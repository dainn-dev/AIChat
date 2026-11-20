package com.aichat.keyboard.core.network.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** `{ "error": { "code", "message", "details"? } }`, produced by the backend's `AllExceptionsFilter`. */
@Serializable
internal data class ErrorEnvelope(val error: ErrorBody)

@Serializable
internal data class ErrorBody(
    val code: String? = null,
    val message: String? = null,
    val details: JsonElement? = null,
)

/** Maps an HTTP error response onto [ApiError]. Lenient: a missing or malformed envelope still maps by status. */
object ErrorEnvelopeMapper {
    private const val SPEND_CAP_METRIC = "spend_cap"

    private val json = Json { ignoreUnknownKeys = true }

    fun fromHttp(status: Int, body: String?): ApiError {
        val envelope = parse(body)
        val code = envelope?.code
        val message = envelope?.message ?: "HTTP $status"
        val details = envelope?.details as? JsonObject

        return when (status) {
            401 -> ApiError.Unauthorized(message)
            400, 422 -> ApiError.Validation(message, details.stringList("fields"))
            402 -> ApiError.QuotaExceeded(message, limit = null, metric = SPEND_CAP_METRIC)
            404 -> ApiError.NotFound(message)
            409 -> ApiError.Conflict(message)
            429 -> ApiError.QuotaExceeded(
                message = message,
                limit = details.int("limit"),
                metric = details.string("metric"),
            )
            502, 503, 504 -> ApiError.Upstream(message, status)
            else -> ApiError.Unknown(message, status, code)
        }
    }

    private fun parse(body: String?): ErrorBody? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(ErrorEnvelope.serializer(), body).error }.getOrNull()
    }

    private fun JsonObject?.int(key: String): Int? = (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject?.stringList(key: String): List<String> =
        (this?.get(key) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            .orEmpty()
}
