package dev.deskseed.audit.internal

import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.SearchExecutedAccessAudit
import dev.deskseed.audit.SearchResultOpenedAccessAudit
import dev.deskseed.audit.TicketResourceReadAccessAudit
import dev.deskseed.audit.TicketViewAccessAudit
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.util.UUID

@Service
internal class JpaAccessAuditWriter(
    private val jdbcTemplate: JdbcTemplate,
) : AccessAuditWriter {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendTicketResourceRead(event: TicketResourceReadAccessAudit) {
        validateStaffContext(event.context)
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, session_fingerprint, auth_type, request_id, correlation_id,
                ip_address, user_agent, outcome, http_status
            ) values (?, ?, ?, ?, ?, ?, 'API_RESOURCE_READ', 'TICKET', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            Timestamp.from(event.occurredAt),
            event.context.actorType.name,
            event.context.actorId,
            actorSnapshot(event.context.actorDisplaySnapshot),
            event.context.source.name,
            event.ticketId,
            event.ticketNumber,
            event.interactionId,
            event.context.sessionFingerprint?.take(100),
            event.context.authType.name,
            event.context.requestId.take(100),
            event.context.correlationId.take(100),
            event.context.ipAddress?.take(64),
            sanitize(event.context.userAgent, 256),
            event.outcome.name,
            event.httpStatus,
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendTicketViewed(event: TicketViewAccessAudit): Boolean {
        validateStaffContext(event.context)
        return jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, session_fingerprint, auth_type, request_id, correlation_id,
                ip_address, user_agent, origin_search_event_id, outcome, http_status
            ) values (?, ?, ?, ?, ?, ?, 'TICKET_VIEWED', 'TICKET', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (actor_id, resource_id, interaction_id, action)
                where action = 'TICKET_VIEWED' and outcome = 'SUCCEEDED'
                do nothing
            """.trimIndent(),
            UUID.randomUUID(),
            Timestamp.from(event.occurredAt),
            event.context.actorType.name,
            event.context.actorId,
            actorSnapshot(event.context.actorDisplaySnapshot),
            event.context.source.name,
            event.ticketId,
            event.ticketNumber,
            event.interactionId,
            event.context.sessionFingerprint?.take(100),
            event.context.authType.name,
            event.context.requestId.take(100),
            event.context.correlationId.take(100),
            event.context.ipAddress?.take(64),
            sanitize(event.context.userAgent, 256),
            event.originSearchEventId,
            event.outcome.name,
            event.httpStatus,
        ) == 1
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendSearchExecuted(event: SearchExecutedAccessAudit) {
        validateStaffContext(event.context)
        require(event.outcome == AccessAuditOutcome.SUCCEEDED) { "Canonical search audit requires success outcome" }
        require(event.resultCount >= 0) { "Search result count cannot be negative" }
        require(event.resultItems.size <= 100 && event.resultItems.size <= event.resultCount) {
            "Search result audit membership must be bounded by the result count"
        }
        require(event.resultItems.map { it.ticketId }.distinct().size == event.resultItems.size) {
            "Search result audit membership cannot contain duplicate tickets"
        }
        require(event.resultItems.map { it.ordinal } == event.resultItems.indices.toList()) {
            "Search result audit membership ordinals must be contiguous"
        }
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, session_fingerprint, auth_type, request_id, correlation_id,
                ip_address, user_agent, outcome, http_status
            ) values (?, ?, ?, ?, ?, ?, 'SEARCH_EXECUTED', 'SEARCH', null, null, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            event.eventId,
            Timestamp.from(event.occurredAt),
            event.context.actorType.name,
            event.context.actorId,
            actorSnapshot(event.context.actorDisplaySnapshot),
            event.context.source.name,
            event.interactionId,
            event.context.sessionFingerprint?.take(100),
            event.context.authType.name,
            event.context.requestId.take(100),
            event.context.correlationId.take(100),
            event.context.ipAddress?.take(64),
            sanitize(event.context.userAgent, 256),
            event.outcome.name,
            event.httpStatus,
        )
        jdbcTemplate.update(
            """
            insert into search_audit_details (
                access_event_id, query_redacted, query_fingerprint, query_key_version,
                normalized_filters, sort, result_count
            ) values (?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent(),
            event.eventId,
            event.protectedQuery.queryRedacted,
            event.protectedQuery.queryFingerprint,
            event.protectedQuery.keyVersion,
            filtersJson(event.normalizedFilters),
            event.sort,
            event.resultCount,
        )
        if (event.resultItems.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                """
                insert into search_audit_result_items (
                    access_event_id, ticket_id, ticket_number, result_ordinal
                ) values (?, ?, ?, ?)
                """.trimIndent(),
                event.resultItems,
                event.resultItems.size,
            ) { statement, item ->
                statement.setObject(1, event.eventId)
                statement.setObject(2, item.ticketId)
                statement.setLong(3, item.ticketNumber)
                statement.setInt(4, item.ordinal)
            }
        }
        jdbcTemplate.update(
            """
            insert into search_audit_query_ciphertexts (
                access_event_id, key_version, query_ciphertext, created_at, expires_at
            ) values (?, ?, ?, ?, ?)
            """.trimIndent(),
            event.eventId,
            event.protectedQuery.keyVersion,
            event.protectedQuery.queryCiphertext,
            Timestamp.from(event.occurredAt),
            Timestamp.from(event.protectedQuery.expiresAt),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    override fun isValidSearchOrigin(
        originSearchEventId: UUID,
        actorId: UUID,
        sessionFingerprint: String,
        ticketId: UUID,
    ): Boolean = jdbcTemplate.queryForObject(
        """
        select exists (
            select 1
            from access_audit_events event
            join search_audit_result_items result_item
              on result_item.access_event_id = event.id
            where event.id = ? and event.action = 'SEARCH_EXECUTED' and event.outcome = 'SUCCEEDED'
              and event.actor_type = 'STAFF' and event.actor_id = ? and event.session_fingerprint = ?
              and result_item.ticket_id = ?
        )
        """.trimIndent(),
        Boolean::class.java,
        originSearchEventId,
        actorId,
        sessionFingerprint,
        ticketId,
    ) == true

    @Transactional(propagation = Propagation.MANDATORY)
    override fun appendSearchResultOpened(event: SearchResultOpenedAccessAudit): Boolean {
        validateStaffContext(event.context)
        return jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, session_fingerprint, auth_type, request_id, correlation_id,
                ip_address, user_agent, origin_search_event_id, outcome, http_status
            ) values (?, ?, ?, ?, ?, ?, 'SEARCH_RESULT_OPENED', 'TICKET', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (actor_id, resource_id, interaction_id, action)
                where action = 'SEARCH_RESULT_OPENED' and outcome = 'SUCCEEDED'
                do nothing
            """.trimIndent(),
            UUID.randomUUID(),
            Timestamp.from(event.occurredAt),
            event.context.actorType.name,
            event.context.actorId,
            actorSnapshot(event.context.actorDisplaySnapshot),
            event.context.source.name,
            event.ticketId,
            event.ticketNumber,
            event.interactionId,
            event.context.sessionFingerprint?.take(100),
            event.context.authType.name,
            event.context.requestId.take(100),
            event.context.correlationId.take(100),
            event.context.ipAddress?.take(64),
            sanitize(event.context.userAgent, 256),
            event.originSearchEventId,
            event.outcome.name,
            event.httpStatus,
        ) == 1
    }

    private fun validateStaffContext(context: AccessAuditContext) {
        require(context.actorType == ActorType.STAFF) { "Staff access audit requires a staff actor" }
        require(context.source == RequestSource.AGENT_UI) { "Staff access audit requires AGENT_UI source" }
        require(context.sessionFingerprint?.isNotBlank() == true) { "Staff access audit requires session context" }
    }

    private fun sanitize(value: String?, maxLength: Int): String? = value
        ?.filterNot { it.isISOControl() }
        ?.take(maxLength)

    private fun actorSnapshot(value: String): String = sanitize(value, 100)
        ?.ifBlank { "STAFF" }
        ?: "STAFF"

    private fun filtersJson(filters: Map<String, String>): String = filters.entries
        .sortedBy(Map.Entry<String, String>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
