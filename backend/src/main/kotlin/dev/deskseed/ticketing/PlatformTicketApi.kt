package dev.deskseed.ticketing

import dev.deskseed.foundation.CommandContext
import java.time.Instant
import java.util.UUID

enum class PlatformTicketKind {
    CUSTOMER_REQUEST,
    INTERNAL_WORK_ITEM,
}

data class PlatformTicketActor(
    val id: UUID,
    val displayName: String,
)

data class CreatePlatformTicketCommand(
    val kind: PlatformTicketKind,
    val requesterId: UUID?,
    val subject: String,
    val message: String,
    val priority: TicketPriority,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val actor: PlatformTicketActor,
    val context: CommandContext,
)

data class UpdatePlatformTicketCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val changedFields: Set<TicketField>,
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val actor: PlatformTicketActor,
    val context: CommandContext,
)

data class AddPlatformInternalCommentCommand(
    val ticketNumber: Long,
    val body: String,
    val actor: PlatformTicketActor,
    val context: CommandContext,
)

data class PlatformTicketView(
    val id: UUID,
    val ticketNumber: Long,
    val kind: PlatformTicketKind,
    val subject: String,
    val status: TicketStatus,
    val priority: TicketPriority,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PlatformInternalCommentView(
    val id: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val ticketVersion: Long,
    val visibility: CommentVisibility,
    val body: String,
    val createdAt: Instant,
)

interface PlatformTicketService {
    fun create(command: CreatePlatformTicketCommand): PlatformTicketView
    fun find(ticketNumber: Long): PlatformTicketView?
    fun update(command: UpdatePlatformTicketCommand): PlatformTicketView
    fun addInternalComment(command: AddPlatformInternalCommentCommand): PlatformInternalCommentView
}

class PlatformTicketNotFoundException : RuntimeException()
class PlatformTicketInvalidException(val code: String) : RuntimeException(code)
class PlatformTicketVersionException(val currentVersion: Long) : RuntimeException()
class PlatformTicketAuditUnavailableException(cause: Throwable) : RuntimeException(cause)

