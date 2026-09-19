package dev.deskseed.aiassistance.internal

import dev.deskseed.ticketing.AiCommentLineageState
import dev.deskseed.ticketing.AiPublicReplyAttributionOutcome
import dev.deskseed.ticketing.AiPublicReplyAttributionRecorder
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.RecordAiPublicReplyAttribution
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JdbcAiPublicReplyAttributionRecorder(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : AiPublicReplyAttributionRecorder {
    override fun record(command: RecordAiPublicReplyAttribution): AiPublicReplyAttributionOutcome {
        val attribution = command.attribution
        var validatedBindings: List<CandidateBinding>? = null
        val outcome = when {
            command.visibility != CommentVisibility.PUBLIC -> AiPublicReplyAttributionOutcome.IGNORED_INTERNAL
            attribution.contractVersion != CONTRACT_VERSION ->
                AiPublicReplyAttributionOutcome.UNATTRIBUTED_VALIDATION_FAILED
            attribution.state == AiCommentLineageState.NO_AI_LINEAGE ->
                AiPublicReplyAttributionOutcome.NO_AI_LINEAGE
            attribution.state == AiCommentLineageState.LINEAGE_LOST ->
                AiPublicReplyAttributionOutcome.UNATTRIBUTED_LINEAGE_LOST
            attribution.sources.isEmpty() || attribution.sources.size > MAX_SOURCES ||
                attribution.sources.map { it.candidateId }.toSet().size != attribution.sources.size ||
                attribution.sources.map { it.jobId }.toSet().size != attribution.sources.size ->
                AiPublicReplyAttributionOutcome.UNATTRIBUTED_VALIDATION_FAILED
            else -> validateSources(command)?.let { bindings ->
                validatedBindings = bindings
                if (bindings.size == 1) {
                    AiPublicReplyAttributionOutcome.ATTRIBUTED_SINGLE
                } else {
                    AiPublicReplyAttributionOutcome.ATTRIBUTED_MULTI
                }
            } ?: AiPublicReplyAttributionOutcome.UNATTRIBUTED_VALIDATION_FAILED
        }
        val sourceCount = attribution.sources.size.coerceAtMost(MAX_SOURCES)
        jdbcTemplate.update(
            """
            insert into ai_reply_attribution_attempts (
                comment_id, ticket_id, requester_staff_id, client_state,
                outcome, source_count, contract_version, created_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            command.commentId,
            command.ticketId,
            command.actorId,
            attribution.state.name,
            outcome.name,
            sourceCount,
            attribution.contractVersion.take(32),
            Timestamp.from(command.occurredAt),
        )
        if (outcome !in setOf(
                AiPublicReplyAttributionOutcome.ATTRIBUTED_SINGLE,
                AiPublicReplyAttributionOutcome.ATTRIBUTED_MULTI,
            )
        ) {
            return outcome
        }
        val bindings = checkNotNull(validatedBindings)
        val edit = if (bindings.size == 1) {
            editMetrics(
                normalizeAiUsageText(attribution.sources.single().originalAnswer),
                normalizeAiUsageText(command.finalBody),
            )
        } else {
            null
        }
        val kind = if (bindings.size == 1) "SINGLE_SOURCE" else "MULTI_SOURCE"
        bindings.forEachIndexed { index, binding ->
            val source = attribution.sources[index]
            jdbcTemplate.update(
                """
                insert into ai_reply_sent_attributions (
                    comment_id, candidate_id, job_id, source_ordinal, attribution_kind, source_count,
                    original_length, final_length, edit_distance, inserted_length, deleted_length,
                    edit_ratio, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                command.commentId,
                source.candidateId,
                source.jobId,
                index + 1,
                kind,
                bindings.size,
                edit?.originalLength,
                edit?.finalLength,
                edit?.distance,
                edit?.inserted,
                edit?.deleted,
                edit?.ratio,
                Timestamp.from(command.occurredAt),
            )
            appendOutbox(command, binding, source.jobId, source.candidateId, kind, bindings.size, edit)
        }
        return outcome
    }

    private fun validateSources(command: RecordAiPublicReplyAttribution): List<CandidateBinding>? {
        val now = Instant.now(clock)
        return command.attribution.sources.map { source ->
            val normalized = normalizeAiUsageText(source.originalAnswer)
            val length = aiUsageCodePointLength(normalized)
            if (length !in 1..MAX_ANSWER_CODE_POINTS) return null
            jdbcTemplate.query(
                """
                select request.workspace_key, request.request_revision,
                       binding.answer_sha256, binding.answer_code_point_length
                from ai_reply_candidate_bindings binding
                join ai_requests request on request.job_id = binding.job_id
                where binding.job_id = ? and binding.candidate_id = ?
                  and binding.requester_staff_id = ? and binding.ticket_id = ?
                  and binding.result_expires_at > ?
                  and binding.feature in ('ticket.reply_draft', 'ticket.reply_rewrite')
                for share of binding, request
                """.trimIndent(),
                { result, _ ->
                    CandidateBinding(
                        workspaceKey = result.getString("workspace_key"),
                        requestRevision = result.getLong("request_revision"),
                        answerSha256 = result.getString("answer_sha256"),
                        answerLength = result.getInt("answer_code_point_length"),
                    )
                },
                source.jobId,
                source.candidateId,
                command.actorId,
                command.ticketId,
                Timestamp.from(now),
            ).singleOrNull()?.takeIf {
                it.answerSha256 == aiUsageSha256(normalized) && it.answerLength == length
            } ?: return null
        }
    }

    private fun appendOutbox(
        command: RecordAiPublicReplyAttribution,
        binding: CandidateBinding,
        jobId: UUID,
        candidateId: UUID,
        attributionKind: String,
        sourceCount: Int,
        edit: EditMetrics?,
    ) {
        val eventId = UUID.randomUUID()
        val envelope = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "eventId" to eventId.toString(),
            "jobId" to jobId.toString(),
            "workspaceKey" to binding.workspaceKey,
            "requesterId" to command.actorId.toString(),
            "commentId" to command.commentId.toString(),
            "candidateId" to candidateId.toString(),
            "attributionKind" to attributionKind,
            "sourceCount" to sourceCount,
            "originalLength" to edit?.originalLength,
            "finalLength" to edit?.finalLength,
            "editDistance" to edit?.distance,
            "insertedLength" to edit?.inserted,
            "deletedLength" to edit?.deleted,
            "editRatio" to edit?.ratio,
            "requestRevision" to binding.requestRevision,
            "sentAt" to command.occurredAt.toString(),
        )
        val payload = objectMapper.writeValueAsString(envelope)
        jdbcTemplate.update(
            """
            insert into ai_integration_outbox (
                event_id, job_id, event_type, schema_version, request_revision,
                payload_json, payload_checksum, status, attempts, available_at, created_at,
                usage_comment_id, usage_candidate_id
            ) values (?, ?, 'REPLY_SENT', 1, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?, ?, ?)
            """.trimIndent(),
            eventId,
            jobId,
            binding.requestRevision,
            payload,
            aiUsageSha256(payload),
            Timestamp.from(command.occurredAt),
            Timestamp.from(command.occurredAt),
            command.commentId,
            candidateId,
        )
    }

    private fun editMetrics(original: String, final: String): EditMetrics {
        val source = original.codePoints().toArray()
        val target = final.codePoints().toArray()
        if (source.contentEquals(target)) {
            return EditMetrics(source.size, target.size, 0, 0, 0, BigDecimal.ZERO.setScale(8))
        }
        var previousDistance = IntArray(target.size + 1) { it }
        var previousInserted = IntArray(target.size + 1) { it }
        var previousDeleted = IntArray(target.size + 1)
        for (sourceIndex in source.indices) {
            val currentDistance = IntArray(target.size + 1)
            val currentInserted = IntArray(target.size + 1)
            val currentDeleted = IntArray(target.size + 1)
            currentDistance[0] = sourceIndex + 1
            currentDeleted[0] = sourceIndex + 1
            for (targetIndex in target.indices) {
                val column = targetIndex + 1
                val substitutionCost = if (source[sourceIndex] == target[targetIndex]) 0 else 1
                var best = EditCell(
                    previousDistance[column - 1] + substitutionCost,
                    previousInserted[column - 1],
                    previousDeleted[column - 1],
                )
                best = bestOf(best, EditCell(
                    previousDistance[column] + 1,
                    previousInserted[column],
                    previousDeleted[column] + 1,
                ))
                best = bestOf(best, EditCell(
                    currentDistance[column - 1] + 1,
                    currentInserted[column - 1] + 1,
                    currentDeleted[column - 1],
                ))
                currentDistance[column] = best.distance
                currentInserted[column] = best.inserted
                currentDeleted[column] = best.deleted
            }
            previousDistance = currentDistance
            previousInserted = currentInserted
            previousDeleted = currentDeleted
        }
        val distance = previousDistance.last()
        val denominator = maxOf(source.size, target.size, 1)
        return EditMetrics(
            originalLength = source.size,
            finalLength = target.size,
            distance = distance,
            inserted = previousInserted.last(),
            deleted = previousDeleted.last(),
            ratio = BigDecimal(distance).divide(BigDecimal(denominator), 8, RoundingMode.HALF_UP)
                .min(BigDecimal.ONE.setScale(8)),
        )
    }

    private fun bestOf(left: EditCell, right: EditCell): EditCell = minOf(
        left,
        right,
        compareBy<EditCell>({ it.distance }, { it.inserted + it.deleted }, { it.deleted }, { it.inserted }),
    )

    private data class CandidateBinding(
        val workspaceKey: String,
        val requestRevision: Long,
        val answerSha256: String,
        val answerLength: Int,
    )

    private data class EditCell(val distance: Int, val inserted: Int, val deleted: Int)

    private data class EditMetrics(
        val originalLength: Int,
        val finalLength: Int,
        val distance: Int,
        val inserted: Int,
        val deleted: Int,
        val ratio: BigDecimal,
    )

    private companion object {
        const val CONTRACT_VERSION = "AI_SENT_V1"
        const val MAX_SOURCES = 4
        const val MAX_ANSWER_CODE_POINTS = 6_000
    }
}
