package dev.deskseed.integration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.integration.INTEGRATION_CLIENT_MANAGE_AUTHORITY
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientNotFoundException
import dev.deskseed.integration.IntegrationClientRatePolicyAdministration
import dev.deskseed.integration.IntegrationClientRatePolicyConflictException
import dev.deskseed.integration.IntegrationClientRatePolicyView
import dev.deskseed.integration.UpdateIntegrationClientRatePolicyCommand
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaIntegrationClientRatePolicyAdministration(
    private val clientRepository: IntegrationClientRepository,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
    @Value("\${deskseed.platform.rate-limit.admin-minimum:1}") private val minimumLimit: Int,
    @Value("\${deskseed.platform.rate-limit.admin-maximum:10000}") private val maximumLimit: Int,
) : IntegrationClientRatePolicyAdministration {
    init {
        require(minimumLimit > 0) { "Platform rate-limit admin minimum must be positive" }
        require(maximumLimit >= minimumLimit) { "Platform rate-limit admin maximum must not be below minimum" }
    }

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun get(clientId: UUID): IntegrationClientRatePolicyView = clientRepository.findById(clientId)
        .map(::toView)
        .orElseThrow(::IntegrationClientNotFoundException)

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional
    override fun update(
        clientId: UUID,
        command: UpdateIntegrationClientRatePolicyCommand,
        actor: IntegrationAdminActor,
    ): IntegrationClientRatePolicyView {
        require(command.expectedVersion >= 0) { "Integration client rate-policy version must be non-negative" }
        require(command.rateLimitPerMinute in minimumLimit..maximumLimit) { "Integration client rate limit is outside admin bounds" }
        val client = clientRepository.findLockedById(clientId) ?: throw IntegrationClientNotFoundException()
        if (client.ratePolicyVersion != command.expectedVersion) {
            throw IntegrationClientRatePolicyConflictException(client.ratePolicyVersion)
        }
        val previousLimit = client.rateLimitPerMinute
        val now = Instant.now(clock)
        client.rateLimitPerMinute = command.rateLimitPerMinute
        client.ratePolicyVersion += 1
        client.updatedAt = now
        clientRepository.saveAndFlush(client)
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "INTEGRATION_CLIENT_RATE_LIMIT_UPDATED",
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "INTEGRATION_CLIENT",
                targetId = client.id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = mapOf(
                    "previousRateLimitPerMinute" to previousLimit.toString(),
                    "rateLimitPerMinute" to command.rateLimitPerMinute.toString(),
                ),
                occurredAt = now,
            ),
        )
        return toView(client)
    }

    private fun toView(client: IntegrationClientEntity) = IntegrationClientRatePolicyView(
        clientId = client.id,
        rateLimitPerMinute = client.rateLimitPerMinute,
        usageCount = client.usageCount,
        lastUsedAt = client.lastUsedAt,
        version = client.ratePolicyVersion,
        updatedAt = client.updatedAt,
    )
}
