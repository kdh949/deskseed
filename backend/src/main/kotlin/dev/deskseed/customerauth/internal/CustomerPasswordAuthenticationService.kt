package dev.deskseed.customerauth.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import dev.deskseed.customerauth.CustomerAuthenticationMethod
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import jakarta.mail.internet.InternetAddress
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.Locale

internal class CustomerPasswordCredentialsInvalidException : RuntimeException()

internal class CustomerPasswordAuthenticationUnavailableException(cause: Throwable? = null) :
    RuntimeException("customer password authentication is unavailable", cause)

private data class PasswordLoginTransactionResult(val session: NewCustomerSession?)

@Service
internal class CustomerPasswordAuthenticationService(
    private val passwordHasher: CustomerPasswordHasher,
    private val rateLimiter: AuthenticationAttemptLimiter,
    private val accountSessionStore: CustomerAccountSessionStore,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) {
    fun login(
        emailInput: String,
        rawPassword: String,
        remoteAddress: String,
        previousRawSession: String?,
        context: CommandContext,
    ): NewCustomerSession {
        val email = requireMailbox(emailInput)
        requirePasswordInput(rawPassword)
        val normalizedEmail = email.lowercase(Locale.ROOT)
        val purpose = CustomerAuthenticationPurpose.PASSWORD_LOGIN
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
                auditFailure("CUSTOMER_PASSWORD_LOGIN_RATE_LIMITED", decision, context)
            }
            throw CustomerAuthenticationRateLimitedException(requireNotNull(decision.retryAfter))
        }

        val candidate = try {
            accountSessionStore.findPasswordLoginCandidate(normalizedEmail)
        } catch (failure: RuntimeException) {
            throw CustomerPasswordAuthenticationUnavailableException(failure)
        }
        val matches = passwordHasher.matchesOrDummy(rawPassword, candidate?.passwordHash)
        if (!matches || candidate == null || !candidate.isEligible()) {
            requiredTransaction {
                auditFailure("CUSTOMER_PASSWORD_LOGIN_FAILED", decision, context)
            }
            throw CustomerPasswordCredentialsInvalidException()
        }

        val result = requiredTransaction {
            val current = accountSessionStore.lockAndFindPasswordLoginCandidate(normalizedEmail)
            if (current == null || !candidate.sameCredentialAs(current) || !current.isEligible()) {
                auditFailure("CUSTOMER_PASSWORD_LOGIN_FAILED", decision, context)
                PasswordLoginTransactionResult(null)
            } else {
                val account = CustomerAccountIdentity(
                    accountId = current.accountId,
                    principal = current.principal,
                    credentialVersion = current.credentialVersion,
                )
                val session = accountSessionStore.createSession(
                    account = account,
                    previousRawSession = previousRawSession,
                    authenticationMethod = CustomerAuthenticationMethod.PASSWORD,
                ).also { created ->
                    auditSuccess(created, context)
                }
                PasswordLoginTransactionResult(session)
            }
        }
        return result.session ?: throw CustomerPasswordCredentialsInvalidException()
    }

    private fun CustomerPasswordLoginCandidate.isEligible(): Boolean =
        status == "ACTIVE" && passwordHash != null

    private fun CustomerPasswordLoginCandidate.sameCredentialAs(other: CustomerPasswordLoginCandidate?): Boolean =
        other != null &&
            accountId == other.accountId &&
            status == other.status &&
            credentialVersion == other.credentialVersion &&
            passwordHash == other.passwordHash

    private fun auditSuccess(session: NewCustomerSession, context: CommandContext) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_PASSWORD_LOGIN_SUCCEEDED",
                actorType = ActorType.CUSTOMER,
                actorId = session.principal.customerId,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = session.principal.accountId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun auditFailure(
        eventType: String,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
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
                outcome = AdminSecurityOutcome.DENIED,
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

    private fun <T> requiredTransaction(action: () -> T): T = try {
        transactionTemplate.execute { action() }
            ?: throw CustomerPasswordAuthenticationUnavailableException()
    } catch (failure: CustomerPasswordAuthenticationUnavailableException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw CustomerPasswordAuthenticationUnavailableException(failure)
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

    private fun requirePasswordInput(rawPassword: String) {
        require(rawPassword.codePointCount(0, rawPassword.length) in 1..128 && rawPassword.none(Char::isISOControl)) {
            "password input is invalid"
        }
    }
}
