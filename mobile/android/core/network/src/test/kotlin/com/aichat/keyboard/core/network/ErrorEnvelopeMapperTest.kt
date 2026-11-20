package com.aichat.keyboard.core.network

import com.aichat.keyboard.core.network.error.ApiError
import com.aichat.keyboard.core.network.error.ErrorEnvelopeMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ErrorEnvelopeMapperTest {
    private fun body(code: String, message: String, details: String? = null): String {
        val detailsPart = details?.let { ""","details":$it""" } ?: ""
        return """{"error":{"code":"$code","message":"$message"$detailsPart}}"""
    }

    @Test
    fun `401 maps to Unauthorized`() {
        val error = ErrorEnvelopeMapper.fromHttp(401, body("UNAUTHORIZED", "Invalid email or password."))
        assertEquals(ApiError.Unauthorized("Invalid email or password."), error)
    }

    @Test
    fun `400 maps to Validation with the class-validator fields`() {
        val error = ErrorEnvelopeMapper.fromHttp(
            400,
            body("BAD_REQUEST", "Request validation failed", """{"fields":["email must be an email"]}"""),
        )
        assertEquals(ApiError.Validation("Request validation failed", listOf("email must be an email")), error)
    }

    @Test
    fun `429 QUOTA_EXCEEDED carries the limit and metric`() {
        val error = ErrorEnvelopeMapper.fromHttp(
            429,
            body(
                "QUOTA_EXCEEDED",
                "Daily reply limit reached. Upgrade to Pro for more.",
                """{"metric":"reply","limit":20,"scope":"daily"}""",
            ),
        )
        assertEquals(
            ApiError.QuotaExceeded("Daily reply limit reached. Upgrade to Pro for more.", limit = 20, metric = "reply"),
            error,
        )
    }

    @Test
    fun `402 spend cap maps to QuotaExceeded without a limit`() {
        val error = ErrorEnvelopeMapper.fromHttp(
            402,
            body("SPEND_CAP_EXCEEDED", "Daily spend cap reached.", """{"scope":"daily","cap_usd":2.5}"""),
        )
        assertIs<ApiError.QuotaExceeded>(error)
        assertEquals(null, error.limit)
        assertEquals("spend_cap", error.metric)
    }

    @Test
    fun `a 429 that is not QUOTA_EXCEEDED (gateway rate limit) maps to Upstream`() {
        val error = ErrorEnvelopeMapper.fromHttp(429, body("RATE_LIMITED", "Too many requests"))
        assertEquals(ApiError.Upstream("Too many requests", 429), error)
    }

    @Test
    fun `every 5xx maps to Upstream`() {
        listOf(500, 501, 503, 504, 599).forEach { status ->
            assertIs<ApiError.Upstream>(ErrorEnvelopeMapper.fromHttp(status, body("INTERNAL_ERROR", "boom")), "$status")
        }
    }

    @Test
    fun `502 maps to Upstream`() {
        val error = ErrorEnvelopeMapper.fromHttp(502, body("UPSTREAM_ERROR", "AI provider unavailable"))
        assertEquals(ApiError.Upstream("AI provider unavailable", 502), error)
    }

    @Test
    fun `404 and 409 map to NotFound and Conflict`() {
        assertIs<ApiError.NotFound>(ErrorEnvelopeMapper.fromHttp(404, body("CONVERSATION_NOT_FOUND", "Not found")))
        assertIs<ApiError.Conflict>(ErrorEnvelopeMapper.fromHttp(409, body("CONFLICT", "Email taken")))
    }

    @Test
    fun `other 4xx statuses map to Unknown with the code`() {
        val error = ErrorEnvelopeMapper.fromHttp(403, body("FORBIDDEN", "Forbidden"))
        assertEquals(ApiError.Unknown("Forbidden", 403, "FORBIDDEN"), error)
    }

    @Test
    fun `a missing or non-envelope body still maps by status`() {
        assertEquals(ApiError.Unauthorized("HTTP 401"), ErrorEnvelopeMapper.fromHttp(401, null))
        assertEquals(ApiError.Upstream("HTTP 502", 502), ErrorEnvelopeMapper.fromHttp(502, "<html>Bad Gateway</html>"))
        assertEquals(ApiError.Upstream("HTTP 429", 429), ErrorEnvelopeMapper.fromHttp(429, """{"unexpected":true}"""))
    }
}
