package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffIdentity
import dev.deskseed.organization.StaffIdentityService
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

@Service
internal class StaffAuthenticationApplicationService(
    private val staffIdentityService: StaffIdentityService,
    private val throttleStore: StaffLoginThrottleStore,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
    @Value("\${deskseed.staff-auth.login-failure-limit:10}")
    private val failureLimit: Int,
    @Value("\${deskseed.staff-auth.login-failure-window:15m}")
    private val failureWindow: Duration,
) {
    @Transactional(
        noRollbackFor = [
            InvalidStaffCredentialsException::class,
            StaffLoginRateLimitedException::class,
        ],
    )
    fun login(
        email: String,
        password: String,
        remoteAddress: String,
        requestId: String,
        correlationId: String,
    ): StaffIdentity {
        val now = Instant.now(clock)
        val emailFingerprint = fingerprint(email.trim().lowercase())
        val networkFingerprint = fingerprint(remoteAddress.take(100))
        throttleStore.lockedFor(emailFingerprint, networkFingerprint, now)?.let { retryAfter ->
            auditFailure(emailFingerprint, "RATE_LIMITED", requestId, correlationId, now)
            throw StaffLoginRateLimitedException(retryAfter)
        }

        val identity = staffIdentityService.authenticate(email, password)
        if (identity == null) {
            val retryAfter = throttleStore.registerFailure(
                emailFingerprint = emailFingerprint,
                networkFingerprint = networkFingerprint,
                now = now,
                window = failureWindow,
                failureLimit = failureLimit,
            )
            auditFailure(
                emailFingerprint = emailFingerprint,
                reason = if (retryAfter == null) "INVALID_CREDENTIALS" else "RATE_LIMITED",
                requestId = requestId,
                correlationId = correlationId,
                now = now,
            )
            if (retryAfter != null) throw StaffLoginRateLimitedException(retryAfter)
            throw InvalidStaffCredentialsException()
        }

        throttleStore.clear(emailFingerprint, networkFingerprint)
        staffIdentityService.recordSuccessfulLogin(identity.id, now)
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "LOGIN_SUCCEEDED",
                actorType = ActorType.STAFF,
                actorId = identity.id,
                actorDisplaySnapshot = identity.displayName,
                source = RequestSource.AGENT_UI,
                targetType = "STAFF_ACCOUNT",
                targetId = identity.id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = requestId,
                correlationId = correlationId,
                metadata = mapOf("authType" to "PASSWORD"),
                occurredAt = now,
            ),
        )
        return identity
    }

    private fun auditFailure(
        emailFingerprint: String,
        reason: String,
        requestId: String,
        correlationId: String,
        now: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "LOGIN_FAILED",
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = null,
                source = RequestSource.AGENT_UI,
                targetType = "STAFF_LOGIN",
                targetId = null,
                outcome = AdminSecurityOutcome.DENIED,
                requestId = requestId,
                correlationId = correlationId,
                metadata = mapOf(
                    "emailFingerprint" to emailFingerprint.take(16),
                    "reason" to reason,
                ),
                occurredAt = now,
            ),
        )
    }

    private fun fingerprint(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )
}
