package dev.deskseed.customerauth.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptDecision
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyProjection
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.MailRecipient
import dev.deskseed.outboundmail.OutboundMailIntent
import dev.deskseed.outboundmail.OutboundMailPort
import dev.deskseed.outboundmail.RegistrationVerificationMail
import jakarta.mail.internet.InternetAddress
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.Locale

internal class CustomerRegistrationUnavailableException(cause: Throwable? = null) :
    RuntimeException("customer registration is unavailable", cause)

internal data class RequestedRegistrationPolicyVersion(
    val policyKey: String,
    val version: Int,
)

internal data class CustomerRegistrationRequestCommand(
    val email: String,
    val password: String,
    val displayName: String,
    val companyName: String,
    val acceptedPolicies: List<RequestedRegistrationPolicyVersion>,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER REGISTRATION REQUEST COMMAND]"
}

internal data class CustomerRegistrationRequestResult(
    val rawContinuationSecret: String,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER REGISTRATION REQUEST RESULT]"
}

@Service
internal class CustomerRegistrationApplicationService(
    private val policyProjection: CustomerConsentPolicyProjection,
    private val passwordHasher: CustomerPasswordHasher,
    private val intentStore: JdbcCustomerRegistrationIntentStore,
    private val tokenService: JdbcDigestOneTimeTokenService,
    private val rateLimiter: AuthenticationAttemptLimiter,
    private val outboundMailPort: OutboundMailPort,
    private val auditWriter: AdminSecurityAuditWriter,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
    private val transactionTemplate: TransactionTemplate,
) {
    fun request(
        command: CustomerRegistrationRequestCommand,
        remoteAddress: String,
        context: CommandContext,
    ): CustomerRegistrationRequestResult {
        val email = requireMailbox(command.email)
        val normalizedEmail = email.lowercase(Locale.ROOT)
        val displayName = requireProfileValue(command.displayName, 100, "display name")
        val companyName = requireProfileValue(command.companyName, 160, "company name")
        val purpose = CustomerAuthenticationPurpose.REGISTRATION_REQUEST
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
                audit(
                    outcome = AdminSecurityOutcome.DENIED,
                    context = context,
                    metadata = safeFingerprintMetadata(decision),
                )
            }
            throw CustomerAuthenticationRateLimitedException(requireNotNull(decision.retryAfter))
        }

        val selections = currentPolicySelections(command.acceptedPolicies)
        val passwordHash = passwordHasher.encode(command.password)
        return requiredTransaction {
            val created = intentStore.replacePendingIfAccountAbsent(
                NewCustomerRegistrationIntent(
                    emailDisplay = email,
                    passwordHash = passwordHash,
                    displayName = displayName,
                    companyName = companyName,
                    policySelections = selections,
                    ttl = properties.registrationVerificationTtl,
                    context = context,
                ),
            )
            if (created != null) {
                val verification = tokenService.generate(
                    emailDisplay = email,
                    target = CustomerOneTimeTokenTarget.EmailVerification(created.id),
                    ttl = properties.registrationVerificationTtl,
                    context = context,
                )
                outboundMailPort.enqueue(
                    OutboundMailIntent(
                        idempotencyKey = "customer-registration-verification:${verification.id}",
                        recipient = MailRecipient(email),
                        content = RegistrationVerificationMail(
                            "${properties.registrationVerificationUrl}#token=${verification.rawToken}",
                        ),
                        actor = ActorRef(ActorType.SYSTEM, null),
                        context = context,
                    ),
                )
            }
            audit(
                outcome = AdminSecurityOutcome.SUCCEEDED,
                context = context,
                metadata = safeFingerprintMetadata(decision),
            )
            CustomerRegistrationRequestResult(
                created?.rawContinuationSecret ?: CustomerAuthSecrets.randomBearer(),
            )
        }
    }

    private fun currentPolicySelections(
        requested: List<RequestedRegistrationPolicyVersion>,
    ): List<CustomerRegistrationPolicySelection> {
        require(requested.size in 1..20) { "registration policy selection count is invalid" }
        require(requested.distinctBy { it.policyKey }.size == requested.size) {
            "registration policy selections must be unique"
        }
        val current = try {
            policyProjection.current(CustomerConsentContext.REGISTRATION).policies
        } catch (failure: CustomerConsentUnavailableException) {
            throw CustomerRegistrationUnavailableException(failure)
        }
        if (current.none { it.required }) throw CustomerRegistrationUnavailableException()
        val byKey = current.associateBy { it.policyKey }
        val selected = requested.map { candidate ->
            val policy = byKey[candidate.policyKey]
                ?: throw IllegalArgumentException("registration policy selection is invalid")
            require(candidate.version == policy.version) { "registration policy version is stale" }
            CustomerRegistrationPolicySelection(policy.policyId, policy.version)
        }
        val selectedKeys = requested.mapTo(mutableSetOf()) { it.policyKey }
        require(current.filter { it.required }.all { it.policyKey in selectedKeys }) {
            "required registration policy selection is missing"
        }
        return selected
    }

    private fun <T> requiredTransaction(action: () -> T): T = try {
        transactionTemplate.execute { action() }
            ?: throw CustomerRegistrationUnavailableException()
    } catch (failure: CustomerRegistrationUnavailableException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw CustomerRegistrationUnavailableException(failure)
    }

    private fun audit(
        outcome: AdminSecurityOutcome,
        context: CommandContext,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_REGISTRATION_REQUESTED",
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

    private fun safeFingerprintMetadata(decision: AuthenticationAttemptDecision) = mapOf(
        "destinationFingerprint" to decision.destinationFingerprint,
        "networkFingerprint" to decision.requesterNetworkFingerprint,
    )

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
}
