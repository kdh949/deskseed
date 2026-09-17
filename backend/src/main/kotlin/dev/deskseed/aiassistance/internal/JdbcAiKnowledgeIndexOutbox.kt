package dev.deskseed.aiassistance.internal

import dev.deskseed.knowledge.AiKnowledgeIndexAction
import dev.deskseed.knowledge.AiKnowledgeIndexOutbox
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcAiKnowledgeIndexOutbox(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${deskseed.ai.workspace-key:default}") private val workspaceKey: String,
) : AiKnowledgeIndexOutbox {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(
        articleId: UUID,
        revisionId: UUID,
        action: AiKnowledgeIndexAction,
        occurredAt: Instant,
    ) {
        val publicRevision = jdbcTemplate.queryForObject(
            "select content_checksum from knowledge_article_revisions where id = ? and article_id = ?",
            String::class.java,
            revisionId,
            articleId,
        ) ?: error("Knowledge revision checksum is unavailable")
        val eventId = UUID.randomUUID()
        val payload = objectMapper.writeValueAsString(
            linkedMapOf(
                "schemaVersion" to 1,
                "eventId" to eventId.toString(),
                "workspaceKey" to workspaceKey,
                "articleId" to articleId.toString(),
                "revisionId" to revisionId.toString(),
                "action" to action.name,
                "publicRevision" to publicRevision,
                "createdAt" to occurredAt.toString(),
            ),
        )
        jdbcTemplate.update(
            """
            insert into ai_knowledge_index_outbox (
                event_id, workspace_key, article_id, revision_id, action, public_revision,
                payload_json, payload_checksum, status, attempts, available_at, created_at
            ) values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
            on conflict (workspace_key, article_id, revision_id, action) do nothing
            """.trimIndent(),
            eventId,
            workspaceKey,
            articleId,
            revisionId,
            action.name,
            publicRevision,
            payload,
            sha256(payload),
            Timestamp.from(occurredAt),
            Timestamp.from(occurredAt),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
