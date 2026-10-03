package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiActivityAudit
import dev.deskseed.aiassistance.AiActivityAuditWriter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp

@Service
internal class JdbcAiActivityAuditWriter(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : AiActivityAuditWriter {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(event: AiActivityAudit) {
        require(event.action in ACTIONS)
        require(event.requestRevision > 0)
        require(event.details.size <= 10)
        val metadata = event.details.toSortedMap().mapValues { (_, value) ->
            require(value.length <= 100 && value.none(Char::isISOControl))
            value
        }
        jdbcTemplate.update(
            """
            insert into ai_activity_events (
                event_id, occurred_at, actor_id, actor_display_snapshot, source, action,
                job_id, ticket_id, ticket_number, request_revision, request_id,
                correlation_id, session_fingerprint, metadata_json
            ) values (?, ?, ?, ?, 'AGENT_UI', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """.trimIndent(),
            event.eventId,
            Timestamp.from(event.occurredAt),
            event.actor.id,
            event.actor.displayName.take(100),
            event.action,
            event.jobId,
            event.ticketId,
            event.ticketNumber,
            event.requestRevision,
            event.metadata.requestId.take(100),
            event.metadata.correlationId.take(100),
            event.actor.sessionFingerprint,
            objectMapper.writeValueAsString(metadata),
        )
    }

    private companion object {
        val ACTIONS = setOf("AI_REQUEST_CREATED", "AI_REQUEST_CANCELLED", "AI_FEEDBACK_RECORDED")
    }
}
