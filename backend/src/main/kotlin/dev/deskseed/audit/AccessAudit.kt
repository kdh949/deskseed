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
    CUSTOMER_CAPABILITY,
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

data class SavedViewExecutedAccessAudit(
    val context: AccessAuditContext,
    val viewId: UUID,
    val interactionId: UUID,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

data class AttachmentDownloadAccessAudit(
    val context: AccessAuditContext,
    val attachmentId: UUID,
    val ticketNumber: Long,
    val interactionId: UUID?,
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
    val resultItems: List<SearchResultAuditItem>,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

data class SearchResultAuditItem(
    val ticketId: UUID,
    val ticketNumber: Long,
    val ordinal: Int,
)

data class CustomerSearchExecutedAccessAudit(
    val eventId: UUID,
    val context: AccessAuditContext,
    val interactionId: UUID,
    val protectedQuery: ProtectedSearchQueryAudit,
    val resultCount: Long,
    val resultItems: List<CustomerSearchResultAuditItem>,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

data class CustomerSearchResultAuditItem(
    val customerId: UUID,
    val ordinal: Int,
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

interface SearchQueryRevealer {
    fun reveal(eventId: UUID, protected: ProtectedSearchQueryAudit): String
}

class SearchQueryKeyUnavailableException :
    AccessAuditProtectionException("Protected search query key version is unavailable")

open class SearchQueryAuthenticationException(cause: Throwable) :
    AccessAuditProtectionException("Protected search query authentication failed", cause)

fun interface AccessAuditSessionFingerprint {
    fun fingerprint(sessionId: String): String
}

interface AccessAuditWriter {
    /** Appends one required access audit for every successful protected ticket-detail read. */
    fun appendTicketResourceRead(event: TicketResourceReadAccessAudit)

    /** Appends the required explicit execution/preview access audit for a saved view definition. */
    fun appendSavedViewExecuted(event: SavedViewExecutedAccessAudit)

    /** Required access audit for a private attachment byte stream; failure withholds bytes. */
    fun appendAttachmentDownloaded(event: AttachmentDownloadAccessAudit)

    /** Returns true when a new semantic view was appended, false for a duplicate interaction. */
    fun appendTicketViewed(event: TicketViewAccessAudit): Boolean

    fun appendSearchExecuted(event: SearchExecutedAccessAudit)

    fun appendCustomerSearchExecuted(event: CustomerSearchExecutedAccessAudit)

    fun isValidSearchOrigin(
        originSearchEventId: UUID,
        actorId: UUID,
        sessionFingerprint: String,
        ticketId: UUID,
    ): Boolean

    /** Returns true when a new result-open event was appended, false for a duplicate interaction. */
    fun appendSearchResultOpened(event: SearchResultOpenedAccessAudit): Boolean
}
