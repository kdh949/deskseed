package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.TicketCommandIdReusedException
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketCommandWarning
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
internal class StaffTicketCommandReplayStore(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun lock(actorId: UUID, commandId: String) {
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "$actorId:$commandId",
        )
    }

    fun find(actorId: UUID, commandId: String): StaffTicketCommandReplay? {
        val matches = jdbcTemplate.query(
            """
            select audit.id as audit_id, ticket.ticket_number, audit.ticket_version,
                   first_event.metadata_json::jsonb ->> 'commandOperation' as command_operation,
                   first_event.metadata_json::jsonb ->> 'commandRequestDescriptor' as request_descriptor,
                   coalesce(
                       (
                           select event.metadata_json::jsonb -> 'commandWarnings'
                           from ticket_audit_events event
                           where event.audit_id = audit.id
                           order by event.event_order
                           limit 1
                       ),
                       '[]'::jsonb
                   )::text as warnings_json
            from ticket_audits audit
            join tickets ticket on ticket.id = audit.ticket_id
            left join lateral (
                select event.metadata_json
                from ticket_audit_events event
                where event.audit_id = audit.id
                order by event.event_order
                limit 1
            ) first_event on true
            where audit.actor_type = 'STAFF'
              and audit.actor_id = ?
              and audit.command_id = ?
            order by audit.created_at, audit.id
            limit 2
            """.trimIndent(),
            { result, _ ->
                StaffTicketCommandReplay(
                    operation = result.getString("command_operation"),
                    requestDescriptor = result.getString("request_descriptor"),
                    result = TicketCommandResult(
                        ticketNumber = result.getLong("ticket_number"),
                        version = result.getLong("ticket_version"),
                        auditId = result.getObject("audit_id", UUID::class.java),
                        warnings = decodeWarnings(result.getString("warnings_json")),
                    ),
                )
            },
            actorId,
            commandId,
        )
        if (matches.size > 1) throw TicketCommandIdReusedException()
        return matches.singleOrNull()
    }

    private fun decodeWarnings(json: String): List<TicketCommandWarning> =
        objectMapper.readValue(json, Array<StoredCommandWarning>::class.java).map { warning ->
            TicketCommandWarning(
                code = warning.code,
                message = warning.message,
                relatedTicketNumbers = warning.relatedTicketNumbers,
            )
        }

    private data class StoredCommandWarning(
        val code: String,
        val message: String,
        val count: Int,
        val relatedTicketNumbers: List<Long>,
    )
}

internal data class StaffTicketCommandReplay(
    val operation: String?,
    val requestDescriptor: String?,
    val result: TicketCommandResult,
)
