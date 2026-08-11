package dev.deskseed.ticketing

import dev.deskseed.foundation.CommandContext
import java.util.UUID

enum class TicketField(val externalName: String) {
    STATUS("status"),
    PRIORITY("priority"),
    GROUP_ID("groupId"),
    ASSIGNEE_ID("assigneeId");

    companion object {
        fun fromExternalName(value: String): TicketField? = entries.firstOrNull { it.externalName == value }
    }
}

data class StaffTicketCommandActor(
    val id: UUID,
    val displayName: String,
    val isAdmin: Boolean,
)

data class AgentCommentDraft(
    val visibility: CommentVisibility,
    val body: String,
)

data class CreateAgentTicketCommand(
    val requesterId: UUID,
    val subject: String,
    val firstComment: AgentCommentDraft,
    val priority: TicketPriority,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class UpdateAgentTicketCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val changedFields: Set<TicketField>,
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val comment: AgentCommentDraft?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class TransferTicketCommand(
    val ticketNumber: Long,
    val expectedVersion: Long,
    val groupId: UUID,
    val assigneeId: UUID?,
    val reason: String?,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class CreateChildTicketCommand(
    val parentTicketNumber: Long,
    val expectedVersion: Long,
    val subject: String,
    val body: String,
    val groupId: UUID,
    val assigneeId: UUID?,
    val priority: TicketPriority,
    val actor: StaffTicketCommandActor,
    val context: CommandContext,
)

data class TicketCommandWarning(
    val code: String,
    val message: String,
    val relatedTicketNumbers: List<Long>,
) {
    val count: Int = relatedTicketNumbers.size
}

data class TicketCommandResult(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val warnings: List<TicketCommandWarning> = emptyList(),
)

data class CreateChildTicketResult(
    val parentTicketNumber: Long,
    val parentVersion: Long,
    val childTicketNumber: Long,
    val parentAuditId: UUID,
    val childAuditId: UUID,
)

interface AgentTicketCommandService {
    fun create(command: CreateAgentTicketCommand): TicketCommandResult

    fun update(command: UpdateAgentTicketCommand): TicketCommandResult

    fun transfer(command: TransferTicketCommand): TicketCommandResult

    fun createChild(command: CreateChildTicketCommand): CreateChildTicketResult
}

interface TicketWriteAuthorizationPolicy {
    fun canUpdate(
        actor: StaffTicketCommandActor,
        currentGroupId: UUID?,
        currentAssigneeId: UUID?,
    ): Boolean
}

interface TicketAssignmentPolicy {
    fun isActiveGroup(groupId: UUID): Boolean

    fun isActiveMember(groupId: UUID, staffId: UUID): Boolean
}

class AgentTicketNotFoundException : RuntimeException()

class TicketWriteForbiddenException : RuntimeException()

class TicketAssignmentInvalidException(val reason: String) : RuntimeException(reason)

class TicketTransitionInvalidException(val reason: String) : RuntimeException(reason)

class TicketCommandInvalidException(val reason: String) : RuntimeException(reason)

class TicketFieldConflictException(
    val currentVersion: Long,
    val conflictingFields: List<String>,
) : RuntimeException()

class TicketVersionPreconditionFailedException(val currentVersion: Long) : RuntimeException()

class TicketRelationInvalidException(val reason: String) : RuntimeException(reason)

class TicketAuditUnavailableException(cause: Throwable) : RuntimeException(cause)

class TicketUpdateContentionException(cause: Throwable) : RuntimeException(cause)
