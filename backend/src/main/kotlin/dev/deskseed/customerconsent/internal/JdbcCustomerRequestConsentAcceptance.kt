package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CustomerRequestConsentAcceptance
import dev.deskseed.customerconsent.CustomerRequestPolicySelection
import dev.deskseed.customerconsent.CustomerRequestConsentConflictException
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyProjection
import dev.deskseed.customerconsent.CustomerConsentPolicyContextLock
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicy
import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcCustomerRequestConsentAcceptance(
    private val jdbc: JdbcTemplate,
    private val projection: CustomerConsentPolicyProjection,
    private val contextLock: CustomerConsentPolicyContextLock,
    private val clock: Clock,
    private val auditWriter: AdminSecurityAuditWriter,
) : CustomerRequestConsentAcceptance {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun validate(selections: List<CustomerRequestPolicySelection>) { resolve(selections) }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(customerId: UUID, ticketId: UUID, selections: List<CustomerRequestPolicySelection>, context: CommandContext) {
        require(context.source == RequestSource.CUSTOMER_PORTAL)
        val now = Instant.now(clock)
        resolve(selections).forEach { policy ->
            jdbc.update("""
                insert into customer_consent_acceptances
                    (id, customer_id, account_id, ticket_id, policy_id, policy_version,
                     context, accepted_at, source, request_id, correlation_id)
                values (?, ?, null, ?, ?, ?, 'REQUEST_SUBMISSION', ?, 'CUSTOMER_PORTAL', ?, ?)
            """.trimIndent(), UUID.randomUUID(), customerId, ticketId, policy.policyId, policy.version,
                Timestamp.from(now), context.requestId, context.correlationId)
            auditWriter.append(AdminSecurityAudit(
                eventType = "CUSTOMER_CONSENT_ACCEPTED", actorType = ActorType.CUSTOMER,
                actorId = customerId, actorDisplaySnapshot = null, source = context.source,
                targetType = "TICKET", targetId = ticketId, outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = context.requestId, correlationId = context.correlationId,
                metadata = mapOf("policyId" to policy.policyId.toString(), "policyVersion" to policy.version.toString(),
                    "context" to CustomerConsentContext.REQUEST_SUBMISSION.name), occurredAt = now,
            ))
        }
    }

    private fun resolve(selections: List<CustomerRequestPolicySelection>): List<CurrentCustomerConsentPolicy> {
        if (selections.size > 20 || selections.distinctBy { it.policyKey }.size != selections.size)
            throw CustomerRequestConsentConflictException()
        contextLock.lock(CustomerConsentContext.REQUEST_SUBMISSION)
        val policies = projection.current(CustomerConsentContext.REQUEST_SUBMISSION).policies
        val selected = selections.map { selection -> policies.singleOrNull {
            it.policyKey == selection.policyKey && it.version == selection.version
        } ?: throw CustomerRequestConsentConflictException() }
        if (policies.any { it.required && it !in selected }) throw CustomerRequestConsentConflictException()
        return selected
    }
}
