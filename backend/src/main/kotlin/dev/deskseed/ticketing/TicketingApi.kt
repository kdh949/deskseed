package dev.deskseed.ticketing

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContext
import java.time.Instant
import java.util.UUID

enum class TicketKind {
    CUSTOMER_REQUEST,
    INTERNAL_CHILD,
    AGENT_CREATED,
    INTERNAL_WORK_ITEM,
}

enum class TicketStatus {
    NEW,
    OPEN,
    PENDING,
    ON_HOLD,
    SOLVED,
    CLOSED,
}

enum class TicketPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT,
}

enum class TicketChannel {
    WEB,
    AGENT,
    EMAIL,
    CHAT,
    API,
}

enum class CommentVisibility {
    PUBLIC,
    INTERNAL,
}

enum class CommentAuthorType {
    CUSTOMER,
    AGENT,
    INTEGRATION_CLIENT,
    SYSTEM,
    AUTOMATION,
}

enum class CustomerRequestStatus {
    NEW,
    OPEN,
    PENDING,
    SOLVED,
}

data class SubmitPublicRequestCommand(
    val requesterId: UUID,
    val subject: String,
    val message: String,
    val actor: ActorRef,
    val context: CommandContext,
)

data class SubmittedTicket(
    val ticketId: UUID,
    val ticketNumber: Long,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
)

data class PublicCommentView(
    val id: UUID,
    val authorDisplayName: String,
    val body: String,
    val createdAt: Instant,
)

data class PublicTicketView(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val comments: List<PublicCommentView>,
)

interface TicketingFacade {
    fun submitPublicRequest(command: SubmitPublicRequestCommand): SubmittedTicket
    fun findPublicTicket(ticketId: UUID, ticketNumber: Long): PublicTicketView?
}

interface TicketAssignmentUsage {
    fun hasTicketsAssignedToStaff(staffId: UUID): Boolean

    fun hasTicketsInGroup(groupId: UUID): Boolean

    fun hasTicketsAssignedToMember(groupId: UUID, staffId: UUID): Boolean
}

data class TicketSubmitted(
    val ticketId: UUID,
    val ticketNumber: Long,
    val requesterId: UUID?,
    val kind: TicketKind,
    val priority: TicketPriority,
    val groupId: UUID?,
    val channel: TicketChannel,
    val status: TicketStatus,
    val ticketAuditId: UUID,
    val actorType: String,
    val actorId: UUID?,
    val source: String,
    val requestId: String,
    val correlationId: String,
    val startsFirstReplySla: Boolean,
    val occurredAt: Instant,
)

data class TicketSlaLifecycleChanged(
    val ticketId: UUID,
    val previousStatus: TicketStatus,
    val currentStatus: TicketStatus,
    val humanStaffPublicReply: Boolean,
    val ticketAuditId: UUID,
    val actorId: UUID,
    val source: String,
    val requestId: String,
    val correlationId: String,
    val occurredAt: Instant,
)

/**
 * A PUBLIC staff comment persisted in the current transaction that needs Portal-owned customer capability issuance
 * and mail intent creation. The fact is synchronous and transaction-local, not an external delivery guarantee.
 */
data class PublicAgentReplyRecorded(
    val ticketId: UUID,
    val ticketNumber: Long,
    val requesterId: UUID,
    val commentId: UUID,
    val publicBody: String,
    val actor: ActorRef,
    val context: CommandContext,
    val ticketAuditId: UUID,
)
