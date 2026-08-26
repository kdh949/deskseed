package dev.deskseed.customerauth.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import dev.deskseed.customerconsent.CustomerConsentAcceptanceWriter
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyProjection
import dev.deskseed.customerconsent.CustomerConsentPolicyContextLock
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicy
import dev.deskseed.customerconsent.CustomerRegistrationConsentSelection
import dev.deskseed.customerconsent.RecordCustomerRegistrationConsents
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

internal class CustomerRegistrationVerificationInvalidException : RuntimeException(null, null, false, false)
internal class CustomerRegistrationVerificationConflictException : RuntimeException(null, null, false, false)

@Service
internal class CustomerRegistrationVerificationService(
    private val tokenService: JdbcDigestOneTimeTokenService,
    private val intentStore: JdbcCustomerRegistrationIntentStore,
    private val accountStore: CustomerAccountSessionStore,
    private val policyProjection: CustomerConsentPolicyProjection,
    private val policyContextLock: CustomerConsentPolicyContextLock,
    private val acceptanceWriter: CustomerConsentAcceptanceWriter,
    private val rateLimiter: AuthenticationAttemptLimiter,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) {
    fun verify(
        rawToken: String,
        rawContinuationSecret: String?,
        remoteAddress: String,
        context: CommandContext,
    ) {
        require(rawToken.length in 32..256) { "registration verification token is invalid" }
        val purpose = CustomerAuthenticationPurpose.REGISTRATION_VERIFICATION
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
        if (rawContinuationSecret == null || rawContinuationSecret.length !in 32..256) {
            auditDenied("INVALID_PROOF", decision, context)
            throw CustomerRegistrationVerificationInvalidException()
        }

        try {
            requiredTransaction {
                verifyInTransaction(rawToken, rawContinuationSecret, decision, context)
            }
        } catch (failure: CustomerRegistrationVerificationInvalidException) {
            auditDenied("INVALID_PROOF", decision, context)
            throw failure
        } catch (failure: CustomerRegistrationVerificationConflictException) {
            auditDenied("CONFLICT", decision, context)
            throw failure
        }
    }

    private fun verifyInTransaction(
        rawToken: String,
        rawContinuationSecret: String,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        val token = tokenService.consume(rawToken, CustomerOneTimeTokenPurpose.EMAIL_VERIFICATION)
            ?: throw CustomerRegistrationVerificationInvalidException()
        val intentId = token.registrationIntentId ?: throw CustomerRegistrationVerificationInvalidException()
        accountStore.lockAccountEmail(token.emailNormalized)
        val intent = intentStore.lockPendingByProof(intentId, rawContinuationSecret)
            ?: throw CustomerRegistrationVerificationInvalidException()
        if (intent.emailNormalized != token.emailNormalized || intent.emailDisplay != token.emailDisplay) {
            throw CustomerRegistrationVerificationInvalidException()
        }
        policyContextLock.lock(CustomerConsentContext.REGISTRATION)
        val currentPolicies = currentRegistrationPolicies()
        validateCurrentSelections(intent.policySelections, currentPolicies)
        val account = accountStore.createPasswordAccount(
            NewCustomerPasswordAccount(
                emailNormalized = intent.emailNormalized,
                emailDisplay = intent.emailDisplay,
                displayName = intent.displayName,
                companyName = intent.companyName,
                passwordHash = intent.passwordHash,
            ),
        ) ?: throw CustomerRegistrationVerificationConflictException()
        acceptanceWriter.appendRegistration(
            RecordCustomerRegistrationConsents(
                customerId = account.principal.customerId,
                accountId = account.accountId,
                selections = intent.policySelections.map {
                    CustomerRegistrationConsentSelection(it.policyId, it.policyVersion)
                },
                context = context,
            ),
        )
        if (!intentStore.markConsumed(intent)) {
            throw CustomerRegistrationVerificationConflictException()
        }
        auditRegistrationVerified(account, decision, context)
        intent.policySelections.forEach { selection ->
            auditConsentAccepted(account, selection, context)
        }
    }

    private fun currentRegistrationPolicies() = try {
        policyProjection.current(CustomerConsentContext.REGISTRATION).policies
    } catch (failure: CustomerConsentUnavailableException) {
        throw CustomerRegistrationUnavailableException(failure)
    }

    private fun validateCurrentSelections(
        selected: List<CustomerRegistrationPolicySelection>,
        current: List<CurrentCustomerConsentPolicy>,
    ) {
        if (current.none { it.required }) throw CustomerRegistrationVerificationConflictException()
        val currentById = current.associateBy { it.policyId }
        if (selected.any { currentById[it.policyId]?.version != it.policyVersion }) {
            throw CustomerRegistrationVerificationConflictException()
        }
        val selectedIds = selected.mapTo(mutableSetOf()) { it.policyId }
        if (current.filter { it.required }.any { it.policyId !in selectedIds }) {
            throw CustomerRegistrationVerificationConflictException()
        }
    }

    private fun auditRegistrationVerified(
        account: CustomerAccountIdentity,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_REGISTRATION_VERIFIED",
                actorType = ActorType.CUSTOMER,
                actorId = account.principal.customerId,
                actorDisplaySnapshot = account.principal.displayName,
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

    private fun auditConsentAccepted(
        account: CustomerAccountIdentity,
        selection: CustomerRegistrationPolicySelection,
        context: CommandContext,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_CONSENT_ACCEPTED",
                actorType = ActorType.CUSTOMER,
                actorId = account.principal.customerId,
                actorDisplaySnapshot = account.principal.displayName,
                source = RequestSource.CUSTOMER_PORTAL,
                targetType = "CUSTOMER_ACCOUNT",
                targetId = account.accountId,
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
        reason: String,
        decision: AuthenticationAttemptDecision,
        context: CommandContext,
    ) {
        requiredTransaction {
            auditWriter.append(
                AdminSecurityAudit(
                    eventType = "CUSTOMER_REGISTRATION_VERIFIED",
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

    private fun safeFingerprintMetadata(decision: AuthenticationAttemptDecision) = mapOf(
        "destinationFingerprint" to decision.destinationFingerprint,
        "networkFingerprint" to decision.requesterNetworkFingerprint,
    )

    private fun <T> requiredTransaction(action: () -> T): T = try {
        transactionTemplate.execute { action() }
            ?: throw CustomerRegistrationUnavailableException()
    } catch (failure: CustomerRegistrationVerificationInvalidException) {
        throw failure
    } catch (failure: CustomerRegistrationVerificationConflictException) {
        throw failure
    } catch (failure: CustomerRegistrationUnavailableException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw CustomerRegistrationUnavailableException(failure)
    }
}
