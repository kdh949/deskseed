package dev.deskseed.platformapi.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.AuthenticatedIntegrationClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Component
internal class PlatformSecurityAuditRecorder(
    private val writer: AdminSecurityAuditWriter,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun denied(
        principal: AuthenticatedIntegrationClient,
        requestId: String,
        correlationId: String,
        reason: String,
        operationId: String,
    ) {
        writer.append(
            AdminSecurityAudit(
                eventType = "ACCESS_DENIED",
                actorType = principal.actorType,
                actorId = principal.id,
                actorDisplaySnapshot = principal.name,
                source = RequestSource.PLATFORM_API,
                targetType = "PLATFORM_API_OPERATION",
                targetId = null,
                outcome = AdminSecurityOutcome.DENIED,
                requestId = requestId,
                correlationId = correlationId,
                metadata = mapOf("reason" to reason, "operationId" to operationId),
                occurredAt = Instant.now(clock),
            ),
        )
    }
}

