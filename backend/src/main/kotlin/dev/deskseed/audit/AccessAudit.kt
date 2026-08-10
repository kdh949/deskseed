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

data class TicketViewAccessAudit(
    val actorType: ActorType,
    val actorId: UUID,
    val actorDisplaySnapshot: String,
    val source: RequestSource,
    val ticketId: UUID,
    val ticketNumber: Long,
    val interactionId: UUID,
    val requestId: String,
    val correlationId: String,
    val ipAddress: String?,
    val userAgent: String?,
    val outcome: AccessAuditOutcome,
    val httpStatus: Int,
    val occurredAt: Instant,
)

interface AccessAuditWriter {
    /** Returns true when a new semantic view was appended, false for a duplicate interaction. */
    fun appendTicketViewed(event: TicketViewAccessAudit): Boolean
}
