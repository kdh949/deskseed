package dev.deskseed.customerauth.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.MagicLinkMail
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import jakarta.mail.internet.InternetAddress
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale

internal class CustomerMagicLinkInvalidException : RuntimeException()

@Service
internal class CustomerMagicLinkAuthenticationService(
    private val customerDirectory: CustomerDirectory,
    private val tokenService: JdbcDigestOneTimeTokenService,
    private val rateLimiter: CustomerMagicLinkRateLimiter,
    private val accountSessionStore: CustomerAccountSessionStore,
    private val outboundMailPort: OutboundMailPort,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
) {
    @Transactional
    fun request(email: String, remoteAddress: String, context: CommandContext) {
        val mailbox = requireMailbox(email)
        val normalized = mailbox.lowercase(Locale.ROOT)
        val decision = rateLimiter.acquire(normalized, remoteAddress)
        if (!decision.allowed) {
            audit(
                eventType = "CUSTOMER_MAGIC_LINK_RATE_LIMITED",
                outcome = AdminSecurityOutcome.DENIED,
                context = context,
                metadata = safeFingerprintMetadata(decision),
            )
            return
        }

        audit(
            eventType = "CUSTOMER_MAGIC_LINK_REQUESTED",
            outcome = AdminSecurityOutcome.SUCCEEDED,
            context = context,
            metadata = safeFingerprintMetadata(decision),
        )
        if (!customerDirectory.existsByNormalizedEmail(normalized)) return

        val generated = tokenService.generate(mailbox, context)
        outboundMailPort.enqueue(
            OutboundMailIntent(
                idempotencyKey = "customer-magic-link:${generated.id}",
                recipient = MailRecipient(mailbox),
                content = MagicLinkMail(magicLink(generated.rawToken)),
                actor = ActorRef(ActorType.SYSTEM, null),
                context = context,
            ),
        )
    }

    @Transactional(noRollbackFor = [CustomerMagicLinkInvalidException::class])
    fun consume(
        rawToken: String,
        previousRawSession: String?,
        context: CommandContext,
    ): NewCustomerSession {
        val consumed = tokenService.consume(rawToken)
        if (consumed == null) {
            val failureClass = tokenService.failureClass(rawToken)
            audit(
                eventType = if (failureClass == TokenFailureClass.REPLAYED) {
                    "CUSTOMER_MAGIC_LINK_REPLAYED"
                } else {
                    "CUSTOMER_MAGIC_LINK_FAILED"
                },
                outcome = AdminSecurityOutcome.DENIED,
                context = context,
                metadata = mapOf("reason" to failureClass.name),
            )
            throw CustomerMagicLinkInvalidException()
        }

        val account = accountSessionStore.resolveOrCreateAccount(consumed.emailNormalized, consumed.emailDisplay)
        val session = accountSessionStore.createSession(account, previousRawSession)
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_MAGIC_LINK_CONSUMED",
                actorType = ActorType.CUSTOMER,
                actorId = session.principal.customerId,
                actorDisplaySnapshot = session.principal.displayName,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = session.principal.accountId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                occurredAt = Instant.now(clock),
            ),
        )
        return session
    }

    @Transactional
    fun logout(rawSession: String, context: CommandContext) {
        val principal = accountSessionStore.revoke(rawSession) ?: return
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_SESSION_LOGGED_OUT",
                actorType = ActorType.CUSTOMER,
                actorId = principal.customerId,
                actorDisplaySnapshot = principal.displayName,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = principal.accountId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun audit(
        eventType: String,
        outcome: AdminSecurityOutcome,
        context: CommandContext,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_AUTH",
                targetId = null,
                outcome = outcome,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = metadata,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun safeFingerprintMetadata(decision: MagicLinkRateDecision) = mapOf(
        "destinationFingerprint" to decision.destinationFingerprint,
        "networkFingerprint" to decision.networkFingerprint,
    )

    private fun magicLink(rawToken: String): String {
        val base = URI(properties.consumeUrl)
        require(base.isAbsolute && base.host != null && base.scheme in setOf("http", "https")) {
            "customer magic-link consume URL is invalid"
        }
        require(base.rawQuery == null && base.rawFragment == null) { "customer magic-link consume URL must not have query or fragment" }
        return "$base#token=$rawToken"
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
