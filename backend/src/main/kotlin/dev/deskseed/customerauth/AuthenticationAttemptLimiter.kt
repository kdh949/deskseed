package dev.deskseed.customerauth

import java.time.Duration

enum class CustomerAuthenticationPurpose(val keySegment: String) {
    REGISTRATION_REQUEST("registration-request"),
    REGISTRATION_VERIFICATION("registration-verification"),
    PASSWORD_LOGIN("password-login"),
    MAGIC_LINK_REQUEST("magic-link-request"),
    MAGIC_LINK_CONSUME("magic-link-consume"),
    REGISTRATION_COMPLETION("registration-completion"),
    PASSWORD_RESET_REQUEST("password-reset-request"),
    PASSWORD_RESET("password-reset"),
}

data class AuthenticationAttempt(
    val purpose: CustomerAuthenticationPurpose,
    val destinationFingerprint: String,
    val requesterNetworkFingerprint: String,
) {
    init {
        require(destinationFingerprint.matches(FINGERPRINT)) { "destination fingerprint is invalid" }
        require(requesterNetworkFingerprint.matches(FINGERPRINT)) { "requester network fingerprint is invalid" }
    }

    private companion object {
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
    }
}

data class AuthenticationAttemptDecision(
    val allowed: Boolean,
    val destinationFingerprint: String,
    val requesterNetworkFingerprint: String,
    val retryAfter: Duration? = null,
) {
    init {
        require(allowed == (retryAfter == null)) { "retry-after must be present only for a denied attempt" }
    }
}

fun interface AuthenticationAttemptLimiter {
    fun acquire(attempt: AuthenticationAttempt): AuthenticationAttemptDecision
}
