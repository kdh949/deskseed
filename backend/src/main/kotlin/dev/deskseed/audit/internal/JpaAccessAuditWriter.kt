package dev.deskseed.audit.internal

import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.TicketViewAccessAudit
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.sql.Timestamp

@Service
internal class JpaAccessAuditWriter(
    private val jdbcTemplate: JdbcTemplate,
) : AccessAuditWriter {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendTicketViewed(event: TicketViewAccessAudit): Boolean {
        require(event.actorType == ActorType.STAFF) { "Semantic ticket views require a staff actor" }
        require(event.source == RequestSource.AGENT_UI) { "Semantic ticket views require AGENT_UI source" }
        return jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, request_id, correlation_id, ip_address,
                user_agent, outcome, http_status
            ) values (?, ?, ?, ?, ?, ?, 'TICKET_VIEWED', 'TICKET', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (actor_id, resource_id, interaction_id, action)
                where action = 'TICKET_VIEWED' and outcome = 'SUCCEEDED'
                do nothing
            """.trimIndent(),
            UUID.randomUUID(),
            Timestamp.from(event.occurredAt),
            event.actorType.name,
            event.actorId,
            event.actorDisplaySnapshot.take(100),
            event.source.name,
            event.ticketId,
            event.ticketNumber,
            event.interactionId,
            event.requestId.take(100),
            event.correlationId.take(100),
            event.ipAddress?.take(64),
            event.userAgent?.filterNot { it.isISOControl() }?.take(256),
            event.outcome.name,
            event.httpStatus,
        ) == 1
    }
}
