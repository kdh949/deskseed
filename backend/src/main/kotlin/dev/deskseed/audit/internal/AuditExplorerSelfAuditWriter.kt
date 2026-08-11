package dev.deskseed.audit.internal

import dev.deskseed.audit.AdminSecurityAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class AuditExplorerSelfAuditWriter(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun appendSemanticView(event: AdminSecurityAudit): UUID? {
        require(event.eventType == "AUDIT_LOG_VIEWED")
        require(event.actorId != null)
        val id = UUID.randomUUID()
        val metadataJson = objectMapper.writeValueAsString(
            event.metadata.mapValues { (key, value) ->
                value.filterNot { it.isISOControl() }
                    .take(if (key == "reason") 1000 else 256)
            },
        )
        val inserted = jdbcTemplate.query(
            """
            insert into admin_security_audit_events (
                id, event_type, actor_type, actor_id, actor_display_snapshot, source,
                target_type, target_id, outcome, request_id, correlation_id,
                metadata_json, occurred_at
            ) values (
                :id, :eventType, :actorType, :actorId, :actorDisplaySnapshot, :source,
                :targetType, :targetId, :outcome, :requestId, :correlationId,
                :metadataJson, :occurredAt
            )
            on conflict do nothing
            returning id
            """.trimIndent(),
            mapOf(
                "id" to id,
                "eventType" to event.eventType,
                "actorType" to event.actorType.name,
                "actorId" to event.actorId,
                "actorDisplaySnapshot" to event.actorDisplaySnapshot?.take(100),
                "source" to event.source.name,
                "targetType" to event.targetType.take(60),
                "targetId" to event.targetId,
                "outcome" to event.outcome.name,
                "requestId" to event.requestId,
                "correlationId" to event.correlationId,
                "metadataJson" to metadataJson,
                "occurredAt" to Timestamp.from(event.occurredAt),
            ),
        ) { result, _ -> result.getObject("id", UUID::class.java) }.singleOrNull()
        if (inserted == null) {
            val interactionId = event.metadata["interactionId"] ?: error("Semantic view interaction is missing")
            val existing = jdbcTemplate.queryForObject(
                """
                select count(*) from admin_security_audit_events
                where actor_id = :actorId
                  and event_type = :eventType
                  and target_type = :targetType
                  and coalesce(target_id, '00000000-0000-0000-0000-000000000000'::uuid) =
                      coalesce(cast(:targetId as uuid), '00000000-0000-0000-0000-000000000000'::uuid)
                  and metadata_json::jsonb ->> 'interactionId' = :interactionId
                """.trimIndent(),
                mapOf(
                    "actorId" to event.actorId,
                    "eventType" to event.eventType,
                    "targetType" to event.targetType,
                    "targetId" to event.targetId,
                    "interactionId" to interactionId,
                ),
                Long::class.java,
            ) ?: 0
            check(existing == 1L) { "Semantic audit insert did not persist" }
        }
        return inserted
    }
}

