package dev.deskseed.audit

import dev.deskseed.foundation.ActorType
import java.time.Instant
import java.util.UUID

enum class AuditLedgerType {
    TICKET_CHANGE,
    ACCESS_SEARCH,
    ADMIN_SECURITY,
}

enum class AuditExplorerOutcome {
    SUCCEEDED,
    DENIED,
    FAILED,
}

enum class AuditProjectionState {
    CURRENT,
    DEGRADED,
    REBUILDING,
}

enum class SearchQueryRevealState {
    AVAILABLE,
    RETENTION_EXPIRED,
    KEY_UNAVAILABLE,
}

enum class AuditExportFormat {
    CSV,
    JSONL,
}

data class AuditActivityFilter(
    val from: Instant? = null,
    val to: Instant? = null,
    val ledger: AuditLedgerType? = null,
    val action: String? = null,
    val actorType: ActorType? = null,
    val actorId: UUID? = null,
    val ticketNumber: Long? = null,
    val groupId: UUID? = null,
    val field: String? = null,
    val source: String? = null,
    val outcome: AuditExplorerOutcome? = null,
    val requestId: String? = null,
    val correlationId: String? = null,
    val searchFingerprint: String? = null,
)

data class AuditExplorerActor(
    val type: ActorType,
    val id: UUID?,
    val displayName: String,
)

data class AuditActivity(
    val id: UUID,
    val ledger: AuditLedgerType,
    val action: String,
    val actor: AuditExplorerActor,
    val occurredAt: Instant,
    val ticketNumber: Long?,
    val groupId: UUID?,
    val field: String?,
    val resourceType: String?,
    val resourceId: UUID?,
    val summary: String,
    val source: String,
    val outcome: AuditExplorerOutcome,
    val requestId: String?,
    val correlationId: String?,
    val protectedContentAvailable: Boolean,
    val searchFingerprint: String?,
)

data class AuditProjectionStatus(
    val state: AuditProjectionState,
    val projectedCount: Long,
    val lastRebuiltAt: Instant?,
)

data class AuditActivityPage(
    val items: List<AuditActivity>,
    val nextCursor: String?,
    val snapshotAt: Instant,
    val projection: AuditProjectionStatus,
)

data class AuditFieldChange(
    val field: String,
    val before: Any?,
    val after: Any?,
)

data class AuditOpenedActivity(
    val activityId: UUID,
    val ticketNumber: Long,
    val occurredAt: Instant,
)

data class AuditSearchContext(
    val queryRedacted: String,
    val queryFingerprint: String,
    val filters: Map<String, String>,
    val sort: String?,
    val resultCount: Long,
    val originSearchActivityId: UUID?,
    val openedActivities: List<AuditOpenedActivity>,
    val openedActivityCount: Long,
    val openedActivitiesTruncated: Boolean,
)

data class AuditActivityDetail(
    val activity: AuditActivity,
    val canonicalEventId: UUID,
    val canonicalParentId: UUID?,
    val fieldChange: AuditFieldChange?,
    val interactionId: UUID?,
    val sessionFingerprint: String?,
    val authType: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val search: AuditSearchContext?,
    val metadata: Map<String, Any?>,
)

data class AuditRequestContext(
    val actorId: UUID,
    val actorDisplayName: String,
    val authorities: Set<String>,
    val requestId: String,
    val correlationId: String,
    val interactionId: UUID,
    val sessionFingerprint: String,
    val ipAddress: String?,
    val userAgent: String?,
    val authenticatedAt: Instant?,
    val mfaVerifiedAt: Instant?,
)

data class AuditProjectionRebuildResult(
    val ticketChangeCount: Long,
    val accessSearchCount: Long,
    val adminSecurityCount: Long,
    val totalCount: Long,
    val completedAt: Instant,
    val projection: AuditProjectionStatus,
)

data class SearchQueryRevealResult(
    val activityId: UUID,
    val state: SearchQueryRevealState,
    val rawQuery: String?,
    val keyVersion: String?,
    val revealedAt: Instant?,
)

data class CreateAuditExportCommand(
    val format: AuditExportFormat,
    val filters: AuditActivityFilter,
    val fields: List<String>,
    val reason: String,
)

data class AuditExportArtifact(
    val state: String,
    val generationAvailable: Boolean,
)

data class AuditExportJob(
    val id: UUID,
    val status: String,
    val createdAt: Instant,
    val format: AuditExportFormat,
    val fields: List<String>,
    val artifact: AuditExportArtifact,
)

interface AuditExplorer {
    fun list(
        filters: AuditActivityFilter,
        cursor: String?,
        limit: Int,
        context: AuditRequestContext,
    ): AuditActivityPage

    fun detail(activityId: UUID, context: AuditRequestContext): AuditActivityDetail

    fun revealSearchQuery(
        activityId: UUID,
        reason: String,
        context: AuditRequestContext,
    ): SearchQueryRevealResult

    fun createExport(command: CreateAuditExportCommand, context: AuditRequestContext): AuditExportJob

    fun getExport(jobId: UUID, context: AuditRequestContext): AuditExportJob

    fun rebuild(context: AuditRequestContext): AuditProjectionRebuildResult
}

class AuditActivityNotFoundException : NoSuchElementException("Audit activity was not found")

class AuditProjectionRebuildConflictException(cause: Throwable? = null) :
    IllegalStateException("Audit activity projection rebuild is already running", cause)

class AuditRevealDeniedException : IllegalStateException("Recent authentication is required")

class AuditRevealReasonInvalidException : IllegalArgumentException("A bounded reveal reason is required")

class AuditRevealForbiddenException : IllegalStateException("Search query reveal authority is required")

class AuditRevealTargetInvalidException : IllegalArgumentException("Only one SEARCH_EXECUTED event can be revealed")

class AuditProtectedContentInvalidException(cause: Throwable) :
    IllegalStateException("Protected audit content authentication failed", cause)

class AuditExportNotFoundException : NoSuchElementException("Audit export was not found")
