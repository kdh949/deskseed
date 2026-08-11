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
            event.context.actorDisplaySnapshot.take(100),
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
            event.context.actorDisplaySnapshot.take(100),
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
            event.context.actorDisplaySnapshot.take(100),
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
    ): Boolean = jdbcTemplate.queryForObject(
        """
        select exists (
            select 1 from access_audit_events
            where id = ? and action = 'SEARCH_EXECUTED' and outcome = 'SUCCEEDED'
              and actor_type = 'STAFF' and actor_id = ? and session_fingerprint = ?
        )
        """.trimIndent(),
        Boolean::class.java,
        originSearchEventId,
        actorId,
        sessionFingerprint,
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
            event.context.actorDisplaySnapshot.take(100),
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

    private fun filtersJson(filters: Map<String, String>): String = filters.entries
        .sortedBy(Map.Entry<String, String>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${jsonEscape(key)}\":\"${jsonEscape(value)}\""
        }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
