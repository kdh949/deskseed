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
    val requesterId: UUID,
    val occurredAt: Instant,
)
