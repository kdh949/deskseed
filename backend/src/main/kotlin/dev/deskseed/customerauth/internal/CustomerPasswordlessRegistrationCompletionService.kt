package dev.deskseed.customerauth.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.customerconsent.CustomerConsentAcceptanceWriter
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyContextLock
import dev.deskseed.customerconsent.CustomerConsentPolicyProjection
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.customerconsent.CustomerRegistrationConsentSelection
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicy
import dev.deskseed.customerconsent.RecordCustomerRegistrationConsents
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

internal class CustomerPasswordlessRegistrationConflictException : RuntimeException(null, null, false, false)

internal class CustomerPasswordlessRegistrationUnavailableException(cause: Throwable? = null) :
    RuntimeException("customer passwordless registration completion is unavailable", cause)

internal data class CustomerPasswordlessRegistrationPolicyVersion(
    val policyKey: String,
    val version: Int,
)

internal data class CustomerPasswordlessRegistrationCommand(
    val password: String,
    val displayName: String,
    val companyName: String,
    val acceptedPolicies: List<CustomerPasswordlessRegistrationPolicyVersion>,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORDLESS REGISTRATION COMMAND]"
}

@Service
internal class CustomerPasswordlessRegistrationCompletionService(
    private val accountStore: CustomerAccountSessionStore,
    private val policyContextLock: CustomerConsentPolicyContextLock,
    private val policyProjection: CustomerConsentPolicyProjection,
    private val acceptanceWriter: CustomerConsentAcceptanceWriter,
    private val passwordHasher: CustomerPasswordHasher,
    private val rateLimiter: AuthenticationAttemptLimiter,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) {
    fun complete(
        principal: CustomerPrincipal,
        rawSession: String,
        command: CustomerPasswordlessRegistrationCommand,
        remoteAddress: String,
        context: CommandContext,
    ): NewCustomerSession {
        require(rawSession.length in 32..256 && rawSession.none(Char::isISOControl)) {
            "customer session is invalid"
        }
        val displayName = requireProfileValue(command.displayName, 100, "display name")
        val companyName = requireProfileValue(command.companyName, 160, "company name")
        validateRequestedPolicies(command.acceptedPolicies)
        val purpose = CustomerAuthenticationPurpose.REGISTRATION_COMPLETION
        val decision = rateLimiter.acquire(
            AuthenticationAttempt(
                purpose = purpose,
                destinationFingerprint = CustomerAuthSecrets.fingerprint(
                    properties.fingerprintKey,
                    "${purpose.keySegment}:account:${principal.accountId}",
                ),
                requesterNetworkFingerprint = CustomerAuthSecrets.fingerprint(
                    properties.fingerprintKey,
                    "${purpose.keySegment}:network:$remoteAddress",
                ),
            ),
        )
        if (!decision.allowed) {
            requiredTransaction { auditDenied(principal, "RATE_LIMITED", decision, context) }
            throw CustomerAuthenticationRateLimitedException(requireNotNull(decision.retryAfter))
        }

        val passwordHash = passwordHasher.encode(command.password)
        return try {
            requiredTransaction {
                completeInTransaction(
                    principal = principal,
                    rawSession = rawSession,
                    passwordHash = passwordHash,
                    displayName = displayName,
                    companyName = companyName,
                    requestedPolicies = command.acceptedPolicies,
                    decision = decision,
                    context = context,
                )
            }
        } catch (failure: CustomerPasswordlessRegistrationConflictException) {
            requiredTransaction { auditDenied(principal, "CONFLICT", decision, context) }
            throw failure
        }
    }

    private fun completeInTransaction(
        principal: CustomerPrincipal,
        rawSession: String,
        passwordHash: CustomerPasswordHash,
        displayName: String,
        companyName: String,
        requestedPolicies: List<CustomerPasswordlessRegistrationPolicyVersion>,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ): NewCustomerSession {
        val candidate = accountStore.lockPasswordlessRegistrationCandidate(
            accountId = principal.accountId,
            emailNormalized = principal.email,
            rawSession = rawSession,
        ) ?: throw CustomerPasswordlessRegistrationConflictException()
        if (candidate.customerId != principal.customerId) throw CustomerPasswordlessRegistrationConflictException()

        policyContextLock.lock(CustomerConsentContext.REGISTRATION)
        val selections = currentPolicySelections(requestedPolicies)
        val session = accountStore.completePasswordlessRegistration(
            candidate = candidate,
            passwordHash = passwordHash,
            displayName = displayName,
            companyName = companyName,
        ) ?: throw CustomerPasswordlessRegistrationConflictException()
        acceptanceWriter.appendRegistration(
            RecordCustomerRegistrationConsents(
                customerId = candidate.customerId,
                accountId = candidate.accountId,
                selections = selections,
                context = context,
            ),
        )
        auditCompleted(session, decision, context)
        selections.forEach { auditConsentAccepted(session, it, context) }
        return session
    }

    private fun currentPolicySelections(
        requested: List<CustomerPasswordlessRegistrationPolicyVersion>,
    ): List<CustomerRegistrationConsentSelection> {
        val current = try {
            policyProjection.current(CustomerConsentContext.REGISTRATION).policies
        } catch (failure: CustomerConsentUnavailableException) {
            throw CustomerPasswordlessRegistrationUnavailableException(failure)
        }
        if (current.none { it.required }) throw CustomerPasswordlessRegistrationConflictException()
        val currentByKey = current.associateBy(CurrentCustomerConsentPolicy::policyKey)
        val selections = requested.map { selection ->
            val policy = currentByKey[selection.policyKey]
                ?: throw CustomerPasswordlessRegistrationConflictException()
            if (policy.version != selection.version) throw CustomerPasswordlessRegistrationConflictException()
            CustomerRegistrationConsentSelection(policy.policyId, policy.version)
        }
        val selectedKeys = requested.mapTo(mutableSetOf()) { it.policyKey }
        if (current.filter(CurrentCustomerConsentPolicy::required).any { it.policyKey !in selectedKeys }) {
            throw CustomerPasswordlessRegistrationConflictException()
        }
        return selections
    }

    private fun validateRequestedPolicies(requested: List<CustomerPasswordlessRegistrationPolicyVersion>) {
        require(requested.size in 1..20) { "registration policy selection count is invalid" }
        require(requested.distinctBy { it.policyKey }.size == requested.size) {
            "registration policy selections must be unique"
        }
        require(requested.all { it.policyKey.matches(POLICY_KEY) && it.version >= 1 }) {
            "registration policy selection is invalid"
        }
    }

    private fun auditCompleted(
        session: NewCustomerSession,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_REGISTRATION_COMPLETED",
                actorType = ActorType.CUSTOMER,
                actorId = session.principal.customerId,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = session.principal.accountId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = safeFingerprintMetadata(decision),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun auditConsentAccepted(
        session: NewCustomerSession,
        selection: CustomerRegistrationConsentSelection,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_CONSENT_ACCEPTED",
                actorType = ActorType.CUSTOMER,
                actorId = session.principal.customerId,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = session.principal.accountId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = mapOf(
                    "policyId" to selection.policyId.toString(),
                    "policyVersion" to selection.policyVersion.toString(),
                    "context" to CustomerConsentContext.REGISTRATION.name,
                ),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun auditDenied(
        principal: CustomerPrincipal,
        reason: String,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_REGISTRATION_COMPLETED",
                actorType = ActorType.CUSTOMER,
                actorId = principal.customerId,
                actorDisplaySnapshot = null,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = principal.accountId,
                outcome = AdminSecurityOutcome.DENIED,
                requestId = context.requestId,
                correlationId = context.correlationId,
                metadata = safeFingerprintMetadata(decision) + ("reason" to reason),
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun safeFingerprintMetadata(decision: AuthenticationAttemptDecision) = mapOf(
        "destinationFingerprint" to decision.destinationFingerprint,
        "networkFingerprint" to decision.requesterNetworkFingerprint,
    )

    private fun <T> requiredTransaction(action: () -> T): T = try {
        transactionTemplate.execute { action() }
            ?: throw CustomerPasswordlessRegistrationUnavailableException()
    } catch (failure: CustomerPasswordlessRegistrationConflictException) {
        throw failure
    } catch (failure: CustomerPasswordlessRegistrationUnavailableException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw CustomerPasswordlessRegistrationUnavailableException(failure)
    }

    private fun requireProfileValue(value: String, maximumCodePoints: Int, field: String): String {
        val trimmed = value.trim()
        val codePoints = trimmed.codePointCount(0, trimmed.length)
        require(codePoints in 1..maximumCodePoints && trimmed.none(::forbiddenProfileCharacter)) {
            "registration $field is invalid"
        }
        return trimmed
    }

    private fun forbiddenProfileCharacter(character: Char): Boolean =
        character.isISOControl() || character == '<' || character == '>'

    private companion object {
        val POLICY_KEY = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*${'$'}")
    }
}
