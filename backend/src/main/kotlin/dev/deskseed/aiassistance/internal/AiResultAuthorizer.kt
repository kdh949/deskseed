package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiAuditUnavailableException
import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.aiassistance.AiJobReceipt
import dev.deskseed.aiassistance.AiReplyDraftResult
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiRequestNotFoundException
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
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class AiResultAuthorizer(
    private val jdbcTemplate: JdbcTemplate,
    private val ticketStore: StaffTicketReadStore,
    private val knowledgeProjection: AiKnowledgeProjection,
    private val auditWriter: AccessAuditWriter,
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
                   ai_input_revision, input_policy_version, cancellation_requested
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
            computeAiInputRevision(context, AiFeature.fromValue(binding.feature), policyVersion) == binding.aiInputRevision
        } ?: (binding.aiInputRevision == null)
        val contextFresh = remote.contextPolicyVersion == binding.contextPolicyVersion &&
            computeAiContextRevision(context, binding.contextPolicyVersion) == binding.contextRevision &&
            remote.contextRevision == binding.contextRevision && inputFresh
        val resultUnexpired = remote.result == null || remote.resultExpiresAt?.isAfter(Instant.now(clock)) == true
        val provenanceFresh = remote.result == null || remote.provenance?.let { provenance ->
            provenance.contextRevision == binding.contextRevision &&
                provenance.publicCommentIds.all { expected -> context.comments.any { it.id == expected } }
        } == true
        val citationsFresh = (remote.result as? AiReplyDraftResult)?.citations?.all { citation ->
            knowledgeProjection.findCurrentPublic(citation.articleId, citation.revisionId) != null
        } ?: true
        val usable = featureEnabled && !binding.cancelled && contextFresh && resultUnexpired && citationsFresh && provenanceFresh
        if (!usable || remote.result == null) {
            return remote.copy(
                requestRevision = binding.requestRevision,
                cancelRequested = binding.cancelled || remote.cancelRequested,
                stale = !contextFresh || !citationsFresh || !provenanceFresh,
                canInsert = false,
                result = null,
                provenance = null,
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
        return remote.copy(
            requestRevision = binding.requestRevision,
            cancelRequested = false,
            stale = false,
            canInsert = remote.status.name == "SUCCEEDED" && binding.feature == AiFeature.TICKET_REPLY_DRAFT.value,
        )
    }

    private fun isFeatureEnabled(feature: AiFeature, actorId: UUID): Boolean {
        val featureColumn = when (feature) {
            AiFeature.TICKET_SUMMARY -> "summary_enabled"
            AiFeature.TICKET_TRIAGE -> "triage_enabled"
            AiFeature.TICKET_REPLY_DRAFT -> "reply_draft_enabled"
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
    )
}
