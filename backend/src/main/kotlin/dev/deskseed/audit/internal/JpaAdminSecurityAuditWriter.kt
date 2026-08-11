package dev.deskseed.audit.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
internal class JpaAdminSecurityAuditWriter(
    private val repository: AdminSecurityAuditRepository,
    private val objectMapper: ObjectMapper,
) : AdminSecurityAuditWriter {
    @Transactional
    override fun append(event: AdminSecurityAudit): UUID {
        val id = UUID.randomUUID()
        repository.saveAndFlush(
            AdminSecurityAuditEventEntity(
                id = id,
                eventType = event.eventType,
                actorType = event.actorType.name,
                actorId = event.actorId,
                actorDisplaySnapshot = event.actorDisplaySnapshot?.take(100),
                source = event.source.name,
                targetType = event.targetType.take(60),
                targetId = event.targetId,
                outcome = event.outcome.name,
                requestId = event.requestId,
                correlationId = event.correlationId,
                metadataJson = objectMapper.writeValueAsString(
                    event.metadata.mapValues { (key, value) ->
                        value.filterNot { it.isISOControl() }
                            .take(if (key == "reason") 1000 else 256)
                    },
                ),
                occurredAt = event.occurredAt,
            ),
        )
        return id
    }
}
