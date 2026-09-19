package dev.deskseed.aiassistance

import java.time.Instant
import java.util.UUID

enum class AiFeature(val value: String) {
    TICKET_SUMMARY("ticket.summary"),
    TICKET_TRIAGE("ticket.triage"),
    TICKET_REPLY_DRAFT("ticket.reply_draft"),
    TICKET_REPLY_REWRITE("ticket.reply_rewrite");

    companion object {
        fun fromValue(value: String): AiFeature = entries.firstOrNull { it.value == value }
            ?: throw AiRequestInvalidException("Unsupported AI feature")
    }
}

enum class AiBackendRequestStatus {
    ACCEPTED,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    NEEDS_REVIEW,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    EXPIRED,
}

enum class AiGenerationMode {
    REUSE_OR_CREATE,
    NEW_CANDIDATE,
}

data class AiStaffActor(
    val id: UUID,
    val displayName: String,
    val sessionFingerprint: String,
)

data class AiRequestMetadata(
    val requestId: String,
    val correlationId: String,
    val ipAddress: String?,
    val userAgent: String?,
    val traceparent: String? = null,
    val tracestate: String? = null,
)

data class CreateAiRequestCommand(
    val ticketNumber: Long,
    val feature: AiFeature,
    val expectedTicketVersion: Long,
    val sourceJobId: UUID? = null,
    val options: Map<String, String>,
    val generationMode: AiGenerationMode? = null,
    val idempotencyKey: String,
    val actor: AiStaffActor,
    val metadata: AiRequestMetadata,
)

data class AiJobReceipt(
    val jobId: UUID,
    val feature: String,
    val status: AiBackendRequestStatus,
    val requestRevision: Long,
    val createdAt: Instant,
    val deadlineAt: Instant,
    val pollAfterMs: Long,
    val cancelRequested: Boolean,
    val sourceJobId: UUID? = null,
    val inputScope: String = "PUBLIC_ONLY",
    val contextRevision: String,
    val contextPolicyVersion: String,
    val phase: String = "QUEUED",
    val generation: Int? = null,
    val leaseEpoch: Long? = null,
    val completedAt: Instant? = null,
    val resultExpiresAt: Instant? = null,
    val stale: Boolean = false,
    val canInsert: Boolean = false,
    val errorCode: String? = null,
    val result: AiTypedResult? = null,
    val provenance: AiGenerationProvenance? = null,
    val costMicrousd: Long? = null,
    val generationMode: String? = null,
    val candidateSequence: Int? = null,
    val reuseKind: String? = null,
    val providerDispatched: Boolean = false,
)

data class AiGenerationProvenance(
    val modelAlias: String,
    val actualModel: String,
    val promptVersion: String,
    val configVersion: String,
    val generatedAt: Instant,
    val publicCommentIds: List<UUID>,
    val contextRevision: String,
)

sealed interface AiTypedResult {
    val type: String
}

data class AiSummaryResult(
    override val type: String = "ticket.summary",
    val problem: String,
    val attemptedActions: List<String>,
    val unresolvedItems: List<String>,
    val nextChecks: List<String>,
) : AiTypedResult

data class AiTriageResult(
    override val type: String = "ticket.triage",
    val topicCode: String,
    val suggestedTagIds: List<UUID>,
    val suggestedPriority: String?,
    val reasons: List<String>,
) : AiTypedResult

data class AiCitation(
    val articleId: UUID,
    val revisionId: UUID,
    val chunkId: UUID,
    val title: String,
    val url: String,
)

data class AiReplyDraftResult(
    override val type: String = "ticket.reply_draft",
    val answer: String,
    val citations: List<AiCitation>,
) : AiTypedResult

data class AiReplyRewriteResult(
    override val type: String = "ticket.reply_rewrite",
    val answer: String,
    val citations: List<AiCitation>,
    val language: String,
    val tone: String,
    val length: String,
) : AiTypedResult

fun interface AiExecutionStatusReader {
    /** Returns null while AI ingress is unavailable or has not accepted the Backend outbox yet. */
    fun read(jobId: UUID, includeResult: Boolean): AiJobReceipt?
}

interface AiRequestService {
    fun create(command: CreateAiRequestCommand): AiJobReceipt

    fun cancel(
        ticketNumber: Long,
        jobId: UUID,
        actor: AiStaffActor,
        metadata: AiRequestMetadata,
    ): AiJobReceipt

    fun get(
        ticketNumber: Long,
        jobId: UUID,
        includeResult: Boolean,
        actor: AiStaffActor,
        metadata: AiRequestMetadata,
    ): AiJobReceipt

    fun list(ticketNumber: Long, actorId: UUID, limit: Int): List<AiJobReceipt>

    fun feedback(command: RecordAiFeedbackCommand): AiFeedbackReceipt
}

enum class AiFeedbackType(val value: String) {
    HELPFUL("helpful"),
    UNHELPFUL("unhelpful"),
    INSERTED("inserted"),
    EDITED("edited");

    companion object {
        fun fromValue(value: String): AiFeedbackType = entries.firstOrNull { it.value == value }
            ?: throw AiRequestInvalidException("Unsupported AI feedback type")
    }
}

data class RecordAiFeedbackCommand(
    val ticketNumber: Long,
    val jobId: UUID,
    val type: AiFeedbackType,
    val reasonCode: String?,
    val idempotencyKey: String,
    val actor: AiStaffActor,
    val metadata: AiRequestMetadata,
)

data class AiFeedbackReceipt(
    val jobId: UUID,
    val type: String,
    val sourceRevision: Long,
    val replayed: Boolean,
    val recordedAt: Instant,
)

data class AiActivityAudit(
    val eventId: UUID,
    val action: String,
    val actor: AiStaffActor,
    val metadata: AiRequestMetadata,
    val jobId: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val requestRevision: Long,
    val details: Map<String, String>,
    val occurredAt: Instant,
)

fun interface AiActivityAuditWriter {
    fun append(event: AiActivityAudit)
}

data class AiServiceIdentity(
    val id: UUID,
    val displayName: String,
)

data class AiSourceComment(
    val id: UUID,
    val sequence: Long,
    val authorRole: String,
    val body: String,
    val createdAt: Instant,
)

data class AiSourceContext(
    val jobId: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val ticketVersion: Long,
    val feature: String,
    val requestRevision: Long,
    val contextRevision: String,
    val contextPolicyVersion: String,
    val aiInputRevision: String? = null,
    val inputPolicyVersion: String? = null,
    val inputScope: String,
    val comments: List<AiSourceComment>,
)

interface AiSourceContextService {
    fun read(
        jobId: UUID,
        serviceIdentity: AiServiceIdentity,
        metadata: AiRequestMetadata,
    ): AiSourceContext

    fun revision(jobId: UUID): AiSourceRevision

    fun authorizeRewriteSource(
        jobId: UUID,
        serviceIdentity: AiServiceIdentity,
        metadata: AiRequestMetadata,
    ): AiRewriteSourceAuthorization
}

data class AiRewriteSourceAuthorization(
    val rewriteJobId: UUID,
    val sourceJobId: UUID,
    val contextRevision: String,
    val aiInputRevision: String,
    val inputPolicyVersion: String,
    val authorizedAt: Instant,
)

data class AiSourceRevision(
    val jobId: UUID,
    val requestRevision: Long,
    val contextRevision: String,
    val aiInputRevision: String? = null,
    val inputPolicyVersion: String? = null,
    val authorized: Boolean,
    val cancelRequested: Boolean,
    val featureEnabled: Boolean,
)

open class AiRequestException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class AiRequestInvalidException(message: String) : AiRequestException(message)
class AiRequestNotFoundException : AiRequestException("AI request not found")
class AiRequestConflictException : AiRequestException("AI request conflicts with an existing idempotency key")
class AiRequestRateLimitedException(val retryAfterSeconds: Long) : AiRequestException("AI request rate limit exceeded")
class AiPublicContextUnavailableException : AiRequestException("No authorized PUBLIC ticket context is available")
class AiAuditUnavailableException(cause: Throwable) : AiRequestException("Required AI access audit is unavailable", cause)
class AiSourceRequestUnavailableException : AiRequestException("AI source request is unavailable")
class AiSourceRequestSupersededException : AiRequestException("AI source request context changed")
class AiFeatureDisabledException : AiRequestException("AI feature is disabled for the current staff actor")

data class AiSettingsView(
    val enabled: Boolean,
    val summaryEnabled: Boolean,
    val triageEnabled: Boolean,
    val replyDraftEnabled: Boolean,
    val replyRewriteEnabled: Boolean,
    val fastModelAlias: String,
    val standardModelAlias: String,
    val replyRoutingMode: AiReplyRoutingMode,
    val replyRoutingCohorts: List<String>,
    val replyRoutingRolloutPercent: Int,
    val replyRoutingEvaluationApprovalVersion: String?,
    val allowedStaffIds: List<UUID>,
    val version: Long,
    val updatedAt: Instant,
)

data class UpdateAiSettingsCommand(
    val enabled: Boolean,
    val summaryEnabled: Boolean,
    val triageEnabled: Boolean,
    val replyDraftEnabled: Boolean,
    val replyRewriteEnabled: Boolean,
    val fastModelAlias: String,
    val standardModelAlias: String,
    val replyRoutingMode: AiReplyRoutingMode,
    val replyRoutingCohorts: Set<String>,
    val replyRoutingRolloutPercent: Int,
    val replyRoutingEvaluationApprovalVersion: String?,
    val allowedStaffIds: Set<UUID>,
    val expectedVersion: Long,
    val actorId: UUID,
    val actorDisplayName: String,
    val metadata: AiRequestMetadata,
)

enum class AiReplyRoutingMode {
    STANDARD_ONLY,
    EVALUATED_COHORT,
}

data class AiStatusView(
    val enabled: Boolean,
    val settingsVersion: Long,
    val integrationConfigured: Boolean,
    val aiServiceReady: Boolean?,
    val dataAsOf: Instant,
    val aiServiceDataAsOf: Instant?,
    val aiJobCounts: Map<String, Long>,
    val budgetReservedMicrousd: Long?,
    val budgetSettledMicrousd: Long?,
    val budgetUnknownMicrousd: Long?,
    val indexedPublicRevisions: Long?,
    val knowledgeLastReconciledAt: Instant?,
    val deadLetterCount: Long?,
    val requestOutboxPending: Long,
    val requestOutboxDead: Long,
    val knowledgeOutboxPending: Long,
    val knowledgeOutboxDead: Long,
)

data class AiDependencyStatus(
    val ready: Boolean,
    val dataAsOf: Instant,
    val jobCounts: Map<String, Long>,
    val budgetReservedMicrousd: Long,
    val budgetSettledMicrousd: Long,
    val budgetUnknownMicrousd: Long,
    val indexedPublicRevisions: Long,
    val knowledgeLastReconciledAt: Instant?,
    val deadLetterCount: Long,
)

fun interface AiOperationsStatusReader {
    fun read(): AiDependencyStatus?
}

data class AiReindexReceipt(val operationId: UUID, val replayed: Boolean, val itemCount: Int)

interface AiAdministrationService {
    fun settings(): AiSettingsView
    fun update(command: UpdateAiSettingsCommand): AiSettingsView
    fun status(): AiStatusView
    fun reindex(operationId: UUID, actorId: UUID, actorDisplayName: String, metadata: AiRequestMetadata): AiReindexReceipt
}

class AiSettingsConflictException : AiRequestException("AI settings version conflict")
class AiStatusUnavailableException : AiRequestException("AI execution status is temporarily unavailable")
