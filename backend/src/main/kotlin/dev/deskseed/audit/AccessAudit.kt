package dev.deskseed.audit

import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.UUID

enum class AccessAuditOutcome {
    SUCCEEDED,
    DENIED,
    FAILED,
}

enum class AccessAuditAuthType {
    STAFF_SESSION,
    API_KEY,
    OAUTH,
    SYSTEM,
}

data class AccessAuditContext(
    val actorType: ActorType,
    val actorId: UUID,
    val actorDisplaySnapshot: String,
    val source: RequestSource,
    val sessionFingerprint: String?,
    val authType: AccessAuditAuthType,
    val requestId: String,
    val correlationId: String,
    val ipAddress: String?,
    val userAgent: String?,
)

data class TicketViewAccessAudit(
    val context: AccessAuditContext,
    val ticketId: UUID,
    val ticketNumber: Long,
    val interactionId: UUID,
    val originSearchEventId: UUID?,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

data class TicketResourceReadAccessAudit(
    val context: AccessAuditContext,
    val ticketId: UUID,
    val ticketNumber: Long,
    val interactionId: UUID,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

data class ProtectedSearchQueryAudit(
    val queryRedacted: String,
    val queryFingerprint: String,
    val keyVersion: String,
    val queryCiphertext: ByteArray,
    val expiresAt: Instant,
)

data class SearchExecutedAccessAudit(
    val eventId: UUID,
    val context: AccessAuditContext,
    val interactionId: UUID,
    val protectedQuery: ProtectedSearchQueryAudit,
    val normalizedFilters: Map<String, String>,
    val sort: String,
    val resultCount: Long,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

data class SearchResultOpenedAccessAudit(
    val context: AccessAuditContext,
    val ticketId: UUID,
    val ticketNumber: Long,
    val interactionId: UUID,
    val originSearchEventId: UUID,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

open class AccessAuditProtectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

interface SearchQueryProtector {
    fun protect(eventId: UUID, rawQuery: String, occurredAt: Instant): ProtectedSearchQueryAudit
}

fun interface AccessAuditSessionFingerprint {
    fun fingerprint(sessionId: String): String
}

interface AccessAuditWriter {
    /** Appends one required access audit for every successful protected ticket-detail read. */
    fun appendTicketResourceRead(event: TicketResourceReadAccessAudit)

    /** Returns true when a new semantic view was appended, false for a duplicate interaction. */
    fun appendTicketViewed(event: TicketViewAccessAudit): Boolean

    fun appendSearchExecuted(event: SearchExecutedAccessAudit)

    fun isValidSearchOrigin(originSearchEventId: UUID, actorId: UUID, sessionFingerprint: String): Boolean

    /** Returns true when a new result-open event was appended, false for a duplicate interaction. */
    fun appendSearchResultOpened(event: SearchResultOpenedAccessAudit): Boolean
}
