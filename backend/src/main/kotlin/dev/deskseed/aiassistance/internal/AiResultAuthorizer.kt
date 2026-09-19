package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiAuditUnavailableException
import dev.deskseed.aiassistance.AiBackendRequestStatus
import dev.deskseed.aiassistance.AiExecutionStatusReader
import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.aiassistance.AiJobReceipt
import dev.deskseed.aiassistance.AiReplyDraftResult
import dev.deskseed.aiassistance.AiReplyRewriteResult
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiRequestNotFoundException
import dev.deskseed.aiassistance.AiStatusUnavailableException
import dev.deskseed.aiassistance.AiStaffActor
import dev.deskseed.audit.AccessAuditAuthType
import dev.deskseed.audit.AccessAuditContext
import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.AiResultAccessAudit
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.AiKnowledgeProjection
import dev.deskseed.ticketing.StaffTicketReadStore
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class AiResultAuthorizer(
    private val jdbcTemplate: JdbcTemplate,
    private val ticketStore: StaffTicketReadStore,
    private val knowledgeProjection: AiKnowledgeProjection,
    private val auditWriter: AccessAuditWriter,
    private val executionStatusReader: AiExecutionStatusReader,
    private val clock: Clock,
) {
    @Transactional
    fun authorize(
        ticketNumber: Long,
        jobId: UUID,
        actor: AiStaffActor,
        metadata: AiRequestMetadata,
        remote: AiJobReceipt,
    ): AiJobReceipt {
        val binding = jdbcTemplate.query(
            """
            select ticket_id, feature, request_revision, context_revision, context_policy_version,
                   ai_input_revision, input_policy_version, cancellation_requested, source_job_id,
                   generation_mode, candidate_id
            from ai_requests
            where job_id = ? and requester_staff_id = ? and ticket_number = ?
            for share
            """.trimIndent(),
            { result, _ ->
                ResultBinding(
                    ticketId = result.getObject("ticket_id", UUID::class.java),
                    feature = result.getString("feature"),
                    requestRevision = result.getLong("request_revision"),
                    contextRevision = result.getString("context_revision"),
                    contextPolicyVersion = result.getString("context_policy_version"),
                    aiInputRevision = result.getString("ai_input_revision"),
                    inputPolicyVersion = result.getString("input_policy_version"),
                    cancelled = result.getBoolean("cancellation_requested"),
                    sourceJobId = result.getObject("source_job_id", UUID::class.java),
                    generationMode = result.getString("generation_mode"),
                    requestedCandidateId = result.getObject("candidate_id", UUID::class.java),
                )
            },
            jobId,
            actor.id,
            ticketNumber,
        ).singleOrNull() ?: throw AiRequestNotFoundException()
        val context = ticketStore.findAiPublicContext(ticketNumber, actor.id)
            ?: throw AiRequestNotFoundException()
        val featureEnabled = isFeatureEnabled(AiFeature.fromValue(binding.feature), actor.id)
        val inputFresh = binding.inputPolicyVersion?.let { policyVersion ->
            val feature = AiFeature.fromValue(binding.feature)
            if (feature == AiFeature.TICKET_REPLY_REWRITE) {
                val sourceJobId = binding.sourceJobId ?: return@let false
                val sourceInput = jdbcTemplate.query(
                    """
                    select ai_input_revision from ai_requests
                    where job_id = ? and requester_staff_id = ? and ticket_id = ?
                      and feature = 'ticket.reply_draft' and source_job_id is null
                    """.trimIndent(),
                    { result, _ -> result.getString("ai_input_revision") },
                    sourceJobId,
                    actor.id,
                    binding.ticketId,
                ).singleOrNull() ?: return@let false
                computeRewriteInputRevision(context, sourceJobId, sourceInput, policyVersion) == binding.aiInputRevision
            } else {
                computeAiInputRevision(context, feature, policyVersion) == binding.aiInputRevision
            }
        } ?: (binding.aiInputRevision == null)
        val contextFresh = remote.contextPolicyVersion == binding.contextPolicyVersion &&
            computeAiContextRevision(context, binding.contextPolicyVersion) == binding.contextRevision &&
            remote.contextRevision == binding.contextRevision && inputFresh
        val resultUnexpired = remote.result == null || remote.resultExpiresAt?.isAfter(Instant.now(clock)) == true
        val sourceUsable = if (binding.feature == AiFeature.TICKET_REPLY_REWRITE.value) {
            binding.sourceJobId?.let { sourceJobId ->
                executionStatusReader.read(sourceJobId, false)?.let { source ->
                    source.jobId == sourceJobId &&
                        source.feature == AiFeature.TICKET_REPLY_DRAFT.value &&
                        source.status == AiBackendRequestStatus.SUCCEEDED &&
                        !source.cancelRequested && source.sourceJobId == null &&
                        source.inputScope == "PUBLIC_ONLY" &&
                        source.resultExpiresAt?.isAfter(Instant.now(clock)) == true
                } == true
            } == true
        } else {
            true
        }
        val provenanceFresh = remote.result == null || remote.provenance?.let { provenance ->
            provenance.contextRevision == binding.contextRevision &&
                provenance.publicCommentIds.all { expected -> context.comments.any { it.id == expected } }
        } == true
        val citations = when (val result = remote.result) {
            is AiReplyDraftResult -> result.citations
            is AiReplyRewriteResult -> result.citations
            else -> null
        }
        val citationsFresh = citations?.all { citation ->
            knowledgeProjection.findCurrentPublic(citation.articleId, citation.revisionId) != null
        } ?: true
        val candidateFresh = binding.feature !in setOf(
            AiFeature.TICKET_REPLY_DRAFT.value,
            AiFeature.TICKET_REPLY_REWRITE.value,
        ) || binding.requestedCandidateId == null || binding.generationMode != "NEW_CANDIDATE" ||
            remote.candidateId == binding.requestedCandidateId
        val answer = when (val result = remote.result) {
            is AiReplyDraftResult -> result.answer
            is AiReplyRewriteResult -> result.answer
            else -> null
        }
        val normalizedAnswer = answer?.let(::normalizeAiUsageText)
        val answerBounded = normalizedAnswer == null || aiUsageCodePointLength(normalizedAnswer) in 1..6_000
        val usable = featureEnabled && !binding.cancelled && contextFresh && resultUnexpired &&
            sourceUsable && citationsFresh && provenanceFresh && candidateFresh && answerBounded
        if (!usable || remote.result == null) {
            return remote.copy(
                requestRevision = binding.requestRevision,
                cancelRequested = binding.cancelled || remote.cancelRequested,
                stale = !contextFresh || !sourceUsable || !citationsFresh || !provenanceFresh,
                canInsert = false,
                result = null,
                provenance = null,
                candidateId = null,
            )
        }
        try {
            auditWriter.appendAiResultAccess(
                AiResultAccessAudit(
                    eventId = UUID.randomUUID(),
                    context = AccessAuditContext(
                        actorType = ActorType.STAFF,
                        actorId = actor.id,
                        actorDisplaySnapshot = actor.displayName,
                        source = RequestSource.AGENT_UI,
                        sessionFingerprint = actor.sessionFingerprint,
                        authType = AccessAuditAuthType.STAFF_SESSION,
                        requestId = metadata.requestId,
                        correlationId = metadata.correlationId,
                        ipAddress = metadata.ipAddress,
                        userAgent = metadata.userAgent,
                    ),
                    jobId = jobId,
                    requesterStaffId = actor.id,
                    ticketId = binding.ticketId,
                    ticketNumber = ticketNumber,
                    feature = binding.feature,
                    requestRevision = binding.requestRevision,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = Instant.now(clock),
                ),
            )
        } catch (exception: DataAccessException) {
            throw AiAuditUnavailableException(exception)
        }
        if (normalizedAnswer != null && remote.candidateId != null) {
            persistUseBinding(
                binding = binding,
                actorId = actor.id,
                jobId = jobId,
                candidateId = remote.candidateId,
                normalizedAnswer = normalizedAnswer,
                expiresAt = checkNotNull(remote.resultExpiresAt),
            )
        }
        return remote.copy(
            requestRevision = binding.requestRevision,
            cancelRequested = false,
            stale = false,
            canInsert = remote.status.name == "SUCCEEDED" && binding.feature in setOf(
                AiFeature.TICKET_REPLY_DRAFT.value,
                AiFeature.TICKET_REPLY_REWRITE.value,
            ),
        )
    }

    private fun persistUseBinding(
        binding: ResultBinding,
        actorId: UUID,
        jobId: UUID,
        candidateId: UUID,
        normalizedAnswer: String,
        expiresAt: Instant,
    ) {
        val now = Instant.now(clock)
        val answerLength = aiUsageCodePointLength(normalizedAnswer)
        val updated = jdbcTemplate.update(
            """
            insert into ai_reply_candidate_bindings (
                job_id, candidate_id, requester_staff_id, ticket_id, feature,
                answer_sha256, answer_code_point_length, contract_version,
                result_expires_at, bound_at, last_authorized_at
            ) values (?, ?, ?, ?, ?, ?, ?, 'AI_USAGE_TEXT_V1', ?, ?, ?)
            on conflict (job_id, candidate_id) do update set
                result_expires_at = least(ai_reply_candidate_bindings.result_expires_at, excluded.result_expires_at),
                last_authorized_at = excluded.last_authorized_at
            where ai_reply_candidate_bindings.requester_staff_id = excluded.requester_staff_id
              and ai_reply_candidate_bindings.ticket_id = excluded.ticket_id
              and ai_reply_candidate_bindings.feature = excluded.feature
              and ai_reply_candidate_bindings.answer_sha256 = excluded.answer_sha256
              and ai_reply_candidate_bindings.answer_code_point_length = excluded.answer_code_point_length
              and ai_reply_candidate_bindings.contract_version = excluded.contract_version
            """.trimIndent(),
            jobId,
            candidateId,
            actorId,
            binding.ticketId,
            binding.feature,
            aiUsageSha256(normalizedAnswer),
            answerLength,
            Timestamp.from(expiresAt),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        if (updated != 1) throw AiStatusUnavailableException()
    }

    private fun isFeatureEnabled(feature: AiFeature, actorId: UUID): Boolean {
        val featureColumn = when (feature) {
            AiFeature.TICKET_SUMMARY -> "summary_enabled"
            AiFeature.TICKET_TRIAGE -> "triage_enabled"
            AiFeature.TICKET_REPLY_DRAFT -> "reply_draft_enabled"
            AiFeature.TICKET_REPLY_REWRITE -> "reply_rewrite_enabled"
        }
        return jdbcTemplate.queryForObject(
            """
            select exists (
                select 1 from ai_settings settings
                join ai_feature_staff_allowlist allowed on allowed.staff_id = ?
                join staff_accounts staff on staff.id = allowed.staff_id and staff.status = 'ACTIVE'
                where settings.singleton = true and settings.enabled = true and settings.$featureColumn = true
            )
            """.trimIndent(),
            Boolean::class.java,
            actorId,
        ) == true
    }

    private data class ResultBinding(
        val ticketId: UUID,
        val feature: String,
        val requestRevision: Long,
        val contextRevision: String,
        val contextPolicyVersion: String,
        val aiInputRevision: String?,
        val inputPolicyVersion: String?,
        val cancelled: Boolean,
        val sourceJobId: UUID?,
        val generationMode: String?,
        val requestedCandidateId: UUID?,
    )
}
