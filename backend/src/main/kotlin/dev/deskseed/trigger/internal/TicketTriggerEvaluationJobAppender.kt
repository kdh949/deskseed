package dev.deskseed.trigger.internal

import dev.deskseed.ticketing.TicketSubmitted
import dev.deskseed.ticketing.TicketMutationRecorded
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.util.UUID

/**
 * Captures the active ordered version set inside the root ticket transaction.
 * The listener only writes a durable intent; condition/action evaluation belongs to a separate worker.
 */
@Component
internal class TicketTriggerEvaluationJobAppender(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onTicketSubmitted(event: TicketSubmitted) = append(event.ticketId, event.ticketNumber, event.ticketAuditId, event.correlationId, event.occurredAt, "TICKET_CREATED")

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onTicketMutation(event: TicketMutationRecorded) = append(event.ticketId, event.ticketNumber, event.ticketAuditId, event.correlationId, event.occurredAt, if (event.customerReply) "CUSTOMER_REPLIED" else "TICKET_UPDATED")

    private fun append(ticketId: UUID, ticketNumber: Long, auditId: UUID, correlationId: String, occurredAt: java.time.Instant, eventType: String) {
        val versions = jdbc.query(
            """
            select id, active_version, position
             from trigger_definitions
             where active_version is not null
             order by position, id
             limit 100
            """.trimIndent(),
            { result, _ -> TriggerVersionSnapshot(
                result.getObject("id", UUID::class.java),
                result.getInt("active_version"),
                result.getInt("position"),
            ) },
        )
        if (versions.isEmpty()) return
        jdbc.update(
            """
            insert into trigger_evaluation_jobs (
                id, ticket_id, ticket_number, root_ticket_audit_id, root_correlation_id,
                event_type, trigger_versions_json, status, attempt_count, available_at,
                lease_owner, lease_expires_at, last_error_code, created_at, updated_at, completed_at
            ) values (?, ?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING', 0, ?,
                      null, null, null, ?, ?, null)
            on conflict (root_ticket_audit_id, event_type) do nothing
            """.trimIndent(),
            UUID.randomUUID(), ticketId, ticketNumber, auditId, correlationId, eventType,
            objectMapper.writeValueAsString(versions), Timestamp.from(occurredAt),
            Timestamp.from(occurredAt), Timestamp.from(occurredAt),
        )
    }

    private data class TriggerVersionSnapshot(
        val triggerId: UUID,
        val triggerVersion: Int,
        val position: Int,
    )
}
