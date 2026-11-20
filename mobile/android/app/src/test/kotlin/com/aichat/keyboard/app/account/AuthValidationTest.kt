package com.aichat.keyboard.app.account

import com.aichat.keyboard.app.account.AuthValidation.EmailError
import com.aichat.keyboard.app.account.AuthValidation.PasswordError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthValidationTest {
    @Test
    fun `a well-formed login passes, surrounding spaces in the email are tolerated`() {
        assertTrue(AuthValidation.validateLogin(" linh@example.com ", "x").isValid)
    }

    @Test
    fun `malformed emails are rejected`() {
        listOf("", "linh", "linh@", "@example.com", "linh@example", "li nh@example.com").forEach { email ->
            assertEquals(EmailError.INVALID, AuthValidation.validateLogin(email, "x").email, email)
        }
    }

    @Test
    fun `login only requires a non-empty password`() {
        assertEquals(PasswordError.EMPTY, AuthValidation.validateLogin("a@b.co", "").password)
        assertEquals(null, AuthValidation.validateLogin("a@b.co", "short").password)
    }

    @Test
    fun `signup enforces the backend password length bounds`() {
        assertEquals(PasswordError.TOO_SHORT, AuthValidation.validateSignup("a@b.co", "1234567", "1234567").password)
        assertEquals(null, AuthValidation.validateSignup("a@b.co", "12345678", "12345678").password)
        val tooLong = "x".repeat(AuthValidation.PASSWORD_MAX_LENGTH + 1)
        assertEquals(PasswordError.TOO_LONG, AuthValidation.validateSignup("a@b.co", tooLong, tooLong).password)
    }

    @Test
    fun `signup requires matching passwords`() {
        val result = AuthValidation.validateSignup("a@b.co", "password123", "password124")
        assertTrue(result.confirmMismatch)
        assertFalse(result.isValid)
    }
}
