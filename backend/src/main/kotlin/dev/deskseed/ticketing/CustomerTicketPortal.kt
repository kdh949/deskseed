package dev.deskseed.ticketing

import dev.deskseed.foundation.CommandContext
import java.time.Instant
import java.util.UUID

data class CustomerTicketPageQuery(
    val requesterId: UUID,
    val status: CustomerRequestStatus?,
    val beforeUpdatedAt: Instant?,
    val beforeTicketNumber: Long?,
    val limit: Int,
)

data class CustomerTicketSummary(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CustomerTicketPage(
    val items: List<CustomerTicketSummary>,
    val hasMore: Boolean,
)

data class CustomerFollowUpCommand(
    val ticketNumber: Long,
    val requesterId: UUID,
    val requesterEmail: String,
    val body: String,
    val attachmentIds: Set<UUID> = emptySet(),
    val clientCommandId: String,
    val context: CommandContext,
)

data class AnonymousCustomerFollowUpCommand(
    val ticketId: UUID,
    val ticketNumber: Long,
    val body: String,
    val attachmentIds: Set<UUID> = emptySet(),
    val clientCommandId: String,
    val context: CommandContext,
)

data class CustomerFollowUpResult(
    val comment: PublicCommentView,
    val auditId: UUID,
    val ticketId: UUID,
    val requesterId: UUID,
    val replayed: Boolean = false,
)

data class ClaimableCustomerTicket(
    val ticketId: UUID,
    val ticketNumber: Long,
    val requesterId: UUID,
    val requesterEmail: String,
)

data class ClaimCustomerTicketCommand(
    val ticketId: UUID,
    val ticketNumber: Long,
    val accountCustomerId: UUID,
    val accountEmail: String,
    val context: CommandContext,
)

sealed interface CustomerTicketClaimResult {
    data class Claimed(val auditId: UUID) : CustomerTicketClaimResult
    data object NotFound : CustomerTicketClaimResult
    data object Denied : CustomerTicketClaimResult
}

interface CustomerTicketPortal {
    fun list(query: CustomerTicketPageQuery): CustomerTicketPage

    fun detail(requesterId: UUID, ticketNumber: Long): PublicTicketView?

    /** Token-capability callers use this only after the token has resolved the exact ticket id. */
    fun findRequesterId(ticketId: UUID, ticketNumber: Long): UUID?

    fun addFollowUp(command: CustomerFollowUpCommand): CustomerFollowUpResult

    /** Adds a PUBLIC follow-up after Portal has locked a ticket-scoped anonymous capability. */
    fun addAnonymousFollowUp(command: AnonymousCustomerFollowUpCommand): CustomerFollowUpResult

    fun findClaimable(ticketId: UUID, ticketNumber: Long): ClaimableCustomerTicket?

    fun claim(command: ClaimCustomerTicketCommand): CustomerTicketClaimResult
}

class CustomerTicketNotFoundException : RuntimeException()
class CustomerTicketClaimDeniedException : RuntimeException()
class CustomerFollowUpConflictException : RuntimeException()
class CustomerCommandIdReusedException : RuntimeException()
