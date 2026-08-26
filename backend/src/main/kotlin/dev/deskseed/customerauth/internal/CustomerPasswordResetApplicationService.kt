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

@Service
internal class CustomerPasswordResetApplicationService(
    private val resetStore: CustomerPasswordResetStore,
    private val tokenService: JdbcDigestOneTimeTokenService,
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
}
