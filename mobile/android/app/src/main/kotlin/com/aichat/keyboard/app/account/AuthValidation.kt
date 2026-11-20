package com.aichat.keyboard.app.account

/**
 * Client-side checks for the sign-in and sign-up forms. They mirror the backend DTOs (`LoginDto`, `SignupDto`),
 * so most mistakes are caught before a request is sent. The server stays the source of truth.
 */
object AuthValidation {
    const val PASSWORD_MIN_LENGTH = 8
    const val PASSWORD_MAX_LENGTH = 128
    const val EMAIL_MAX_LENGTH = 320
    const val DISPLAY_NAME_MAX_LENGTH = 80

    // Deliberately simple: one "@", a dot in the domain, no whitespace. The backend's IsEmail is stricter.
    private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    enum class EmailError { INVALID }

    enum class PasswordError { EMPTY, TOO_SHORT, TOO_LONG }

    data class Result(
        val email: EmailError? = null,
        val password: PasswordError? = null,
        val confirmMismatch: Boolean = false,
    ) {
        val isValid: Boolean get() = email == null && password == null && !confirmMismatch
    }

    fun validateLogin(email: String, password: String): Result = Result(
        email = validateEmail(email),
        password = when {
            password.isEmpty() -> PasswordError.EMPTY
            password.length > PASSWORD_MAX_LENGTH -> PasswordError.TOO_LONG
            else -> null
        },
    )

    fun validateSignup(email: String, password: String, confirm: String): Result = Result(
        email = validateEmail(email),
        password = when {
            password.isEmpty() -> PasswordError.EMPTY
            password.length < PASSWORD_MIN_LENGTH -> PasswordError.TOO_SHORT
            password.length > PASSWORD_MAX_LENGTH -> PasswordError.TOO_LONG
            else -> null
        },
        confirmMismatch = password != confirm,
    )

    private fun validateEmail(email: String): EmailError? {
        val trimmed = email.trim()
        return if (trimmed.length > EMAIL_MAX_LENGTH || !EMAIL_REGEX.matches(trimmed)) EmailError.INVALID else null
    }
}
