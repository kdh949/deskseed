package dev.deskseed.audit.internal

import dev.deskseed.audit.AuditActivityFilter
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal data class ExportProjectionRow(
    val occurredAt: Instant,
    val id: UUID,
    val ledger: String,
    val action: String,
    val actorType: String,
    val actorId: UUID?,
    val actorDisplayName: String,
    val ticketNumber: Long?,
    val groupId: UUID?,
    val field: String?,
    val source: String,
    val outcome: String,
    val requestId: String?,
    val correlationId: String?,
    val searchFingerprint: String?,
)

/** Streams only already-projected, allowlisted activity columns; it never reaches ciphertext or raw search text. */
@Repository
internal class AuditExportProjectionReader(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    fun forEach(
        filters: AuditActivityFilter,
        snapshotAt: Instant,
        consume: (ExportProjectionRow) -> Unit,
    ): Long {
        requireNotNull(filters.from) { "Export filters must have a resolved lower time bound" }
        requireNotNull(filters.to) { "Export filters must have a resolved upper time bound" }
        var last: Cursor? = null
        var count = 0L
        while (true) {
            val parameters = MapSqlParameterSource()
                .addValue("from", Timestamp.from(filters.from))
                .addValue("to", Timestamp.from(filters.to))
                .addValue("snapshotAt", Timestamp.from(snapshotAt))
                .addValue("snapshotId", MAX_UUID)
                .addValue("limit", PAGE_SIZE)
            val conditions = mutableListOf(
                "occurred_at >= :from",
                "occurred_at < :to",
                "(occurred_at, id) <= (:snapshotAt, :snapshotId)",
            )
            last?.let {
                conditions += "(occurred_at, id) < (:lastOccurredAt, :lastId)"
                parameters.addValue("lastOccurredAt", Timestamp.from(it.occurredAt))
                parameters.addValue("lastId", it.id)
            }
            addFilters(filters, conditions, parameters)
            val rows = jdbcTemplate.query(
                """
                select id, occurred_at, ledger_type, action, actor_type, actor_id, actor_display_snapshot,
                       ticket_number, group_id, field_name, source, outcome, request_id, correlation_id,
                       search_fingerprint
                from audit_activity_projection
                where ${conditions.joinToString("\n  and ")}
                order by occurred_at desc, id desc
                limit :limit
                """.trimIndent(),
                parameters,
            ) { result, _ ->
                ExportProjectionRow(
                    occurredAt = result.getTimestamp("occurred_at").toInstant(),
                    id = result.getObject("id", UUID::class.java),
                    ledger = result.getString("ledger_type"),
                    action = result.getString("action"),
                    actorType = result.getString("actor_type"),
                    actorId = result.getObject("actor_id", UUID::class.java),
                    actorDisplayName = result.getString("actor_display_snapshot"),
                    ticketNumber = result.getObject("ticket_number")?.let { (it as Number).toLong() },
                    groupId = result.getObject("group_id", UUID::class.java),
                    field = result.getString("field_name"),
                    source = result.getString("source"),
                    outcome = result.getString("outcome"),
                    requestId = result.getString("request_id"),
                    correlationId = result.getString("correlation_id"),
                    searchFingerprint = result.getString("search_fingerprint"),
                )
            }
            if (rows.isEmpty()) return count
            rows.forEach {
                consume(it)
                count += 1
            }
            last = rows.last().let { Cursor(it.occurredAt, it.id) }
            if (rows.size < PAGE_SIZE) return count
        }
    }

    private fun addFilters(
        filters: AuditActivityFilter,
        conditions: MutableList<String>,
        parameters: MapSqlParameterSource,
    ) {
        fun add(value: Any?, parameter: String, column: String = parameter) {
            value?.let {
                conditions += "$column = :$parameter"
                parameters.addValue(parameter, it)
            }
        }
        add(filters.ledger?.name, "ledger", "ledger_type")
        add(filters.action, "action")
        add(filters.actorType?.name, "actorType", "actor_type")
        add(filters.actorId, "actorId", "actor_id")
        add(filters.ticketNumber, "ticketNumber", "ticket_number")
        add(filters.groupId, "groupId", "group_id")
        add(filters.field, "field", "field_name")
        add(filters.source, "source")
        add(filters.outcome?.name, "outcome")
        add(filters.requestId, "requestId", "request_id")
        add(filters.correlationId, "correlationId", "correlation_id")
        add(filters.searchFingerprint, "searchFingerprint", "search_fingerprint")
    }

    private data class Cursor(val occurredAt: Instant, val id: UUID)

    private companion object {
        const val PAGE_SIZE = 500
        val MAX_UUID = UUID(-1, -1)
    }
}
