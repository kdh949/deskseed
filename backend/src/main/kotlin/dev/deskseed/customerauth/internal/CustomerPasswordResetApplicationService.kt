package dev.deskseed.customerauth.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.PasswordResetMail
import jakarta.mail.internet.InternetAddress
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.Locale

internal class CustomerPasswordResetUnavailableException(cause: Throwable? = null) :
    RuntimeException("customer password reset is unavailable", cause)

internal class CustomerPasswordResetInvalidException : RuntimeException(null, null, false, false)

@Service
internal class CustomerPasswordResetApplicationService(
    private val resetStore: CustomerPasswordResetStore,
    private val tokenService: JdbcDigestOneTimeTokenService,
    private val passwordHasher: CustomerPasswordHasher,
    private val rateLimiter: AuthenticationAttemptLimiter,
    private val outboundMailPort: OutboundMailPort,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) {
    fun request(
        emailInput: String,
        remoteAddress: String,
        context: CommandContext,
    ) {
        val email = requireMailbox(emailInput)
        val normalizedEmail = email.lowercase(Locale.ROOT)
        val purpose = CustomerAuthenticationPurpose.PASSWORD_RESET_REQUEST
        val decision = rateLimiter.acquire(
            AuthenticationAttempt(
                purpose = purpose,
                destinationFingerprint = CustomerAuthSecrets.fingerprint(
                    properties.fingerprintKey,
                    "${purpose.keySegment}:destination:$normalizedEmail",
                ),
                requesterNetworkFingerprint = CustomerAuthSecrets.fingerprint(
                    properties.fingerprintKey,
                    "${purpose.keySegment}:network:$remoteAddress",
                ),
            ),
        )
        if (!decision.allowed) {
            requiredTransaction {
                audit(AdminSecurityOutcome.DENIED, decision, context)
            }
            throw CustomerAuthenticationRateLimitedException(requireNotNull(decision.retryAfter))
        }

        requiredTransaction {
            resetStore.lockEligibleAccount(normalizedEmail)?.let { accountId ->
                val generated = tokenService.generate(
                    target = CustomerOneTimeTokenTarget.PasswordReset(accountId),
                    ttl = properties.passwordResetTtl,
                    context = context,
                )
                outboundMailPort.enqueue(
                    OutboundMailIntent(
                        idempotencyKey = "customer-password-reset:${generated.id}",
                        recipient = MailRecipient(generated.emailDisplay),
                        content = PasswordResetMail("${properties.passwordResetUrl}#token=${generated.rawToken}"),
                        actor = ActorRef(ActorType.SYSTEM, null),
                        context = context,
                    ),
                )
            }
            audit(AdminSecurityOutcome.SUCCEEDED, decision, context)
        }
    }

    fun consume(
        rawToken: String,
        newPassword: String,
        remoteAddress: String,
        context: CommandContext,
    ) {
        require(rawToken.length in 32..256 && rawToken.none(Char::isISOControl)) {
            "password reset proof is invalid"
        }
        val purpose = CustomerAuthenticationPurpose.PASSWORD_RESET
        val decision = rateLimiter.acquire(
            AuthenticationAttempt(
                purpose = purpose,
                destinationFingerprint = CustomerAuthSecrets.fingerprint(
                    properties.fingerprintKey,
                    "${purpose.keySegment}:proof:$rawToken",
                ),
                requesterNetworkFingerprint = CustomerAuthSecrets.fingerprint(
                    properties.fingerprintKey,
                    "${purpose.keySegment}:network:$remoteAddress",
                ),
            ),
        )
        if (!decision.allowed) {
            auditDenied("RATE_LIMITED", decision, context)
            throw CustomerAuthenticationRateLimitedException(requireNotNull(decision.retryAfter))
        }

        val passwordHash = passwordHasher.encode(newPassword)
        try {
            requiredTransaction {
                consumeInTransaction(rawToken, passwordHash, decision, context)
            }
        } catch (failure: CustomerPasswordResetInvalidException) {
            auditDenied("INVALID_PROOF", decision, context)
            throw failure
        }
    }

    private fun consumeInTransaction(
        rawToken: String,
        passwordHash: CustomerPasswordHash,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        val target = tokenService.findConsumableTarget(rawToken, CustomerOneTimeTokenPurpose.PASSWORD_RESET)
            ?: throw CustomerPasswordResetInvalidException()
        if (target.accountId == null) throw CustomerPasswordResetInvalidException()
        resetStore.lockAccountEmail(target.emailNormalized)
        val token = tokenService.consume(rawToken, CustomerOneTimeTokenPurpose.PASSWORD_RESET)
            ?: throw CustomerPasswordResetInvalidException()
        if (
            token.id != target.id || token.accountId != target.accountId ||
            token.emailNormalized != target.emailNormalized
        ) {
            throw CustomerPasswordResetInvalidException()
        }
        val account = resetStore.replacePassword(token, passwordHash)
            ?: throw CustomerPasswordResetInvalidException()
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_PASSWORD_RESET_COMPLETED",
                actorType = ActorType.CUSTOMER,
                actorId = account.customerId,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = account.accountId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = safeFingerprintMetadata(decision),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun auditDenied(
        reason: String,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        requiredTransaction {
            auditWriter.append(
                AdminSecurityAudit(
                    eventType = "CUSTOMER_PASSWORD_RESET_COMPLETED",
                    actorType = ActorType.SYSTEM,
                    actorId = null,
                    actorDisplaySnapshot = null,
                    source = RequestSource.CUSTOMER_PORTAL,
                    targetType = "CUSTOMER_AUTH",
                    targetId = null,
                    outcome = AdminSecurityOutcome.DENIED,
                    requestId = context.requestId,
                    correlationId = context.correlationId,
                    metadata = safeFingerprintMetadata(decision) + ("reason" to reason),
                    occurredAt = Instant.now(clock),
                ),
            )
        }
    }

    private fun audit(
        outcome: AdminSecurityOutcome,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_PASSWORD_RESET_REQUESTED",
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_AUTH",
                targetId = null,
                outcome = outcome,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = mapOf(
                    "destinationFingerprint" to decision.destinationFingerprint,
                    "networkFingerprint" to decision.requesterNetworkFingerprint,
                ),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun requiredTransaction(action: () -> Unit) {
        try {
            transactionTemplate.executeWithoutResult { action() }
        } catch (failure: CustomerPasswordResetInvalidException) {
            throw failure
        } catch (failure: CustomerPasswordResetUnavailableException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw CustomerPasswordResetUnavailableException(failure)
        }
    }

    private fun requireMailbox(value: String): String {
        require(value == value.trim() && value.length in 3..254 && value.none(Char::isISOControl)) {
            "email is invalid"
        }
        require(value.count { it == '@' } == 1 && value.none(Char::isWhitespace) && !value.contains(',')) {
            "email is invalid"
        }
        val parsed = InternetAddress(value, true).also(InternetAddress::validate)
        require(parsed.address == value && parsed.personal == null) { "email is invalid" }
        val domain = value.substringAfter('@')
        require(domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')) { "email is invalid" }
        return value
    }

    private fun safeFingerprintMetadata(decision: AuthenticationAttemptDecision) = mapOf(
        "destinationFingerprint" to decision.destinationFingerprint,
        "networkFingerprint" to decision.requesterNetworkFingerprint,
    )
}
