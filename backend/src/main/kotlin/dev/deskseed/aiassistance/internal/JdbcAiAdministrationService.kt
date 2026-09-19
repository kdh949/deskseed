package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiAdministrationService
import dev.deskseed.aiassistance.AiReindexReceipt
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiReplyRoutingMode
import dev.deskseed.aiassistance.AiOperationsStatusReader
import dev.deskseed.aiassistance.AiSettingsConflictException
import dev.deskseed.aiassistance.AiSettingsView
import dev.deskseed.aiassistance.AiStatusView
import dev.deskseed.aiassistance.UpdateAiSettingsCommand
import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.AiKnowledgeIndexAction
import dev.deskseed.knowledge.AiKnowledgeIndexOutbox
import dev.deskseed.knowledge.AiKnowledgeProjection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcAiAdministrationService(
    private val jdbcTemplate: JdbcTemplate,
    private val auditWriter: AdminSecurityAuditWriter,
    private val knowledgeProjection: AiKnowledgeProjection,
    private val knowledgeIndexOutbox: AiKnowledgeIndexOutbox,
    private val integrationProperties: AiIntegrationProperties,
    private val operationsStatusReader: AiOperationsStatusReader,
    private val clock: Clock,
) : AiAdministrationService {
    @Transactional(readOnly = true)
    override fun settings(): AiSettingsView {
        val allowed = allowedStaffIds()
        return jdbcTemplate.query(
            """
            select settings.*,
                   array(
                       select cohort_key from ai_reply_routing_cohorts order by cohort_key
                   ) as reply_routing_cohorts
            from ai_settings settings where singleton = true
            """.trimIndent(),
            { result, _ -> mapSettings(result, allowed) },
        )
            .single()
    }

    @Transactional
    override fun update(command: UpdateAiSettingsCommand): AiSettingsView {
        require(command.expectedVersion >= 0) { "expectedVersion must be nonnegative" }
        require(command.fastModelAlias == FAST_MODEL_ALIAS) { "Unsupported fast model alias" }
        require(command.standardModelAlias == STANDARD_MODEL_ALIAS) { "Unsupported standard model alias" }
        validateReplyRouting(command)
        val current = jdbcTemplate.queryForObject(
            "select version from ai_settings where singleton = true for update",
            Long::class.java,
        ) ?: error("AI settings row is unavailable")
        if (current != command.expectedVersion) throw AiSettingsConflictException()
        if (command.allowedStaffIds.isNotEmpty()) {
            command.allowedStaffIds.forEach { staffId ->
                val active = jdbcTemplate.queryForObject(
                    "select exists (select 1 from staff_accounts where id = ? and status = 'ACTIVE')",
                    Boolean::class.java,
                    staffId,
                ) == true
                require(active) { "Allowed staff must all be active" }
            }
        }
        val now = Instant.now(clock)
        jdbcTemplate.update("delete from ai_feature_staff_allowlist")
        command.allowedStaffIds.sortedBy(UUID::toString).forEach { staffId ->
            jdbcTemplate.update(
                """
                insert into ai_feature_staff_allowlist (staff_id, added_by_staff_id, added_at)
                values (?, ?, ?)
                """.trimIndent(),
                staffId,
                command.actorId,
                Timestamp.from(now),
            )
        }
        jdbcTemplate.update("delete from ai_reply_routing_cohorts")
        command.replyRoutingCohorts.sorted().forEach { cohort ->
            jdbcTemplate.update(
                """
                insert into ai_reply_routing_cohorts (cohort_key, added_by_staff_id, added_at)
                values (?, ?, ?)
                """.trimIndent(),
                cohort,
                command.actorId,
                Timestamp.from(now),
            )
        }
        jdbcTemplate.update(
            """
            update ai_settings
            set enabled = ?, summary_enabled = ?, triage_enabled = ?, reply_draft_enabled = ?,
                reply_rewrite_enabled = ?,
                fast_model_alias = ?, standard_model_alias = ?, reply_routing_mode = ?,
                reply_routing_rollout_percent = ?, reply_routing_evaluation_approval_version = ?,
                version = version + 1,
                updated_by_staff_id = ?, updated_at = ?
            where singleton = true and version = ?
            """.trimIndent(),
            command.enabled,
            command.summaryEnabled,
            command.triageEnabled,
            command.replyDraftEnabled,
            command.replyRewriteEnabled,
            command.fastModelAlias,
            command.standardModelAlias,
            command.replyRoutingMode.name,
            command.replyRoutingRolloutPercent,
            command.replyRoutingEvaluationApprovalVersion,
            command.actorId,
            Timestamp.from(now),
            command.expectedVersion,
        ).also { if (it != 1) throw AiSettingsConflictException() }
        appendAudit(
            "AI_SETTINGS_UPDATED",
            command.actorId,
            command.actorDisplayName,
            command.metadata,
            targetId = null,
            metadata = mapOf(
                "version" to (current + 1).toString(),
                "enabled" to command.enabled.toString(),
                "allowlistCount" to command.allowedStaffIds.size.toString(),
                "replyRoutingMode" to command.replyRoutingMode.name,
                "replyRoutingCohortCount" to command.replyRoutingCohorts.size.toString(),
                "replyRoutingRolloutPercent" to command.replyRoutingRolloutPercent.toString(),
                "replyRoutingEvaluationApprovalVersion" to
                    (command.replyRoutingEvaluationApprovalVersion ?: "NONE"),
            ),
        )
        return settings()
    }

    override fun status(): AiStatusView {
        val setting = jdbcTemplate.queryForMap("select enabled, version from ai_settings where singleton = true")
        fun count(table: String, status: String): Long = jdbcTemplate.queryForObject(
            "select count(*) from $table where status = ?",
            Long::class.java,
            status,
        ) ?: 0L
        val dependency = operationsStatusReader.read()
        return AiStatusView(
            enabled = setting.getValue("enabled") as Boolean,
            settingsVersion = (setting.getValue("version") as Number).toLong(),
            integrationConfigured = runCatching { integrationProperties.validate() }.isSuccess && integrationProperties.enabled,
            aiServiceReady = dependency?.ready,
            dataAsOf = Instant.now(clock),
            aiServiceDataAsOf = dependency?.dataAsOf,
            aiJobCounts = dependency?.jobCounts ?: emptyMap(),
            budgetReservedMicrousd = dependency?.budgetReservedMicrousd,
            budgetSettledMicrousd = dependency?.budgetSettledMicrousd,
            budgetUnknownMicrousd = dependency?.budgetUnknownMicrousd,
            indexedPublicRevisions = dependency?.indexedPublicRevisions,
            knowledgeLastReconciledAt = dependency?.knowledgeLastReconciledAt,
            deadLetterCount = dependency?.deadLetterCount,
            requestOutboxPending = count("ai_integration_outbox", "PENDING"),
            requestOutboxDead = count("ai_integration_outbox", "DEAD"),
            knowledgeOutboxPending = count("ai_knowledge_index_outbox", "PENDING"),
            knowledgeOutboxDead = count("ai_knowledge_index_outbox", "DEAD"),
        )
    }

    @Transactional
    override fun reindex(
        operationId: UUID,
        actorId: UUID,
        actorDisplayName: String,
        metadata: AiRequestMetadata,
    ): AiReindexReceipt {
        val fingerprint = sha256("KB_REINDEX\u001f$actorId")
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtext('ai-kb-reindex'), hashtext(?))",
            Any::class.java,
            operationId.toString(),
        )
        jdbcTemplate.query(
            "select request_fingerprint, item_count from ai_admin_operations where operation_id = ?",
            { result, _ -> result.getString("request_fingerprint") to result.getInt("item_count") },
            operationId,
        ).singleOrNull()?.let { existing ->
            if (existing.first != fingerprint) throw AiSettingsConflictException()
            return AiReindexReceipt(operationId, replayed = true, itemCount = existing.second)
        }
        val now = Instant.now(clock)
        val snapshotToken = knowledgeProjection.createManifestSnapshot(now, now.plusSeconds(MANIFEST_SNAPSHOT_TTL_SECONDS))
        var cursor: UUID? = null
        var itemCount = 0
        do {
            val page = checkNotNull(knowledgeProjection.manifest(snapshotToken, cursor, MANIFEST_PAGE_SIZE, now)) {
                "AI knowledge manifest snapshot expired during reindex"
            }
            if (itemCount + page.items.size > MAX_REINDEX_ITEMS) {
                throw AiSettingsConflictException()
            }
            page.items.forEach { item ->
                knowledgeIndexOutbox.append(item.articleId, item.revisionId, AiKnowledgeIndexAction.UPSERT, now)
            }
            itemCount += page.items.size
            cursor = page.nextCursor
        } while (cursor != null)
        jdbcTemplate.update(
            "delete from ai_knowledge_manifest_snapshots where snapshot_token = ?",
            snapshotToken,
        )
        jdbcTemplate.update(
            """
            insert into ai_admin_operations (
                operation_id, operation_type, request_fingerprint, requested_by_staff_id, item_count, created_at
            ) values (?, 'KB_REINDEX', ?, ?, ?, ?)
            """.trimIndent(),
            operationId,
            fingerprint,
            actorId,
            itemCount,
            Timestamp.from(now),
        )
        appendAudit(
            "AI_KB_REINDEX_REQUESTED",
            actorId,
            actorDisplayName,
            metadata,
            targetId = operationId,
            metadata = mapOf("itemCount" to itemCount.toString()),
        )
        return AiReindexReceipt(operationId, replayed = false, itemCount = itemCount)
    }

    private companion object {
        const val FAST_MODEL_ALIAS = "openai/gpt-5.6-luna"
        const val STANDARD_MODEL_ALIAS = "openai/gpt-5.6-terra"
        const val REPLY_ROUTING_COHORT = "reply-single-public-article-short-v1"
        const val MANIFEST_PAGE_SIZE = 500
        const val MAX_REINDEX_ITEMS = 10_000
        const val MANIFEST_SNAPSHOT_TTL_SECONDS = 24 * 60 * 60L
    }

    private fun allowedStaffIds(): List<UUID> = jdbcTemplate.queryForList(
        "select staff_id from ai_feature_staff_allowlist order by staff_id",
        UUID::class.java,
    ).filterNotNull()

    private fun mapSettings(
        result: ResultSet,
        allowed: List<UUID>,
    ) = AiSettingsView(
        enabled = result.getBoolean("enabled"),
        summaryEnabled = result.getBoolean("summary_enabled"),
        triageEnabled = result.getBoolean("triage_enabled"),
        replyDraftEnabled = result.getBoolean("reply_draft_enabled"),
        replyRewriteEnabled = result.getBoolean("reply_rewrite_enabled"),
        fastModelAlias = result.getString("fast_model_alias"),
        standardModelAlias = result.getString("standard_model_alias"),
        replyRoutingMode = AiReplyRoutingMode.valueOf(result.getString("reply_routing_mode")),
        replyRoutingCohorts =
            (result.getArray("reply_routing_cohorts").array as Array<*>)
                .map { it.toString() },
        replyRoutingRolloutPercent = result.getInt("reply_routing_rollout_percent"),
        replyRoutingEvaluationApprovalVersion =
            result.getString("reply_routing_evaluation_approval_version"),
        allowedStaffIds = allowed,
        version = result.getLong("version"),
        updatedAt = result.getTimestamp("updated_at").toInstant(),
    )

    private fun validateReplyRouting(command: UpdateAiSettingsCommand) {
        require(command.replyRoutingCohorts.all { it == REPLY_ROUTING_COHORT }) {
            "Unsupported reply routing cohort"
        }
        when (command.replyRoutingMode) {
            AiReplyRoutingMode.STANDARD_ONLY -> {
                require(command.replyRoutingCohorts.isEmpty()) {
                    "STANDARD_ONLY cannot approve reply routing cohorts"
                }
                require(command.replyRoutingRolloutPercent == 0) {
                    "STANDARD_ONLY requires zero rollout"
                }
                require(command.replyRoutingEvaluationApprovalVersion == null) {
                    "STANDARD_ONLY cannot have an evaluation approval"
                }
            }
            AiReplyRoutingMode.EVALUATED_COHORT -> {
                require(command.replyRoutingCohorts == setOf(REPLY_ROUTING_COHORT)) {
                    "EVALUATED_COHORT requires the approved reply cohort"
                }
                require(command.replyRoutingRolloutPercent in setOf(10, 50, 100)) {
                    "EVALUATED_COHORT rollout must be 10, 50, or 100"
                }
                require(
                    command.replyRoutingEvaluationApprovalVersion?.matches(
                        Regex("^[a-z0-9][a-z0-9._-]{0,79}$"),
                    ) == true,
                ) { "EVALUATED_COHORT requires a bounded evaluation approval version" }
            }
        }
    }

    private fun appendAudit(
        eventType: String,
        actorId: UUID,
        actorDisplayName: String,
        request: AiRequestMetadata,
        targetId: UUID?,
        metadata: Map<String, String>,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actorId,
                actorDisplaySnapshot = actorDisplayName,
                source = RequestSource.ADMIN_UI,
                targetType = "AI_CONFIGURATION",
                targetId = targetId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = request.requestId,
                correlationId = request.correlationId,
                metadata = metadata,
                occurredAt = Instant.now(clock),
            ),
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

}
