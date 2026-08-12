package dev.deskseed.staffaccess.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.foundation.CommandContext
import dev.deskseed.organization.StaffRole
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.CreateAgentTicketCommand
import dev.deskseed.ticketing.CreateChildTicketCommand
import dev.deskseed.ticketing.CreateChildTicketResult
import dev.deskseed.ticketing.CreateTicketExternalReferenceCommand
import dev.deskseed.ticketing.DeleteTicketExternalReferenceCommand
import dev.deskseed.ticketing.StaffTicketCommandActor
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketExternalReferenceCommandResult
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TransferTicketCommand
import dev.deskseed.ticketing.UpdateAgentTicketCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.time.Instant
import dev.deskseed.integration.ExternalObjectType

internal data class CreateAgentTicketInput(
    val requesterName: String,
    val requesterEmail: String,
    val subject: String,
    val firstComment: AgentCommentDraft,
    val priority: TicketPriority,
    val groupId: UUID?,
    val assigneeId: UUID?,
)

internal data class UpdateAgentTicketInput(
    val expectedVersion: Long,
    val changedFields: Set<TicketField>,
    val status: TicketStatus?,
    val priority: TicketPriority?,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val comment: AgentCommentDraft?,
)

internal data class TransferTicketInput(
    val expectedVersion: Long,
    val groupId: UUID,
    val assigneeId: UUID?,
    val reason: String?,
)

internal data class CreateChildTicketInput(
    val expectedVersion: Long,
    val subject: String,
    val body: String,
    val groupId: UUID,
    val assigneeId: UUID?,
    val priority: TicketPriority,
)

internal data class CreateTicketExternalReferenceInput(
    val expectedVersion: Long,
    val externalSystemId: UUID,
    val objectType: ExternalObjectType,
    val externalId: String,
    val displayLabel: String,
    val safeDeepLink: String,
    val metadata: Map<String, Any>,
    val metadataObservedAt: Instant,
)

@Service
internal class AgentTicketCommandApplicationService(
    private val customerDirectory: CustomerDirectory,
    private val ticketCommandService: AgentTicketCommandService,
) {
    @Transactional
    fun create(
        principal: StaffPrincipal,
        input: CreateAgentTicketInput,
        context: CommandContext,
    ): TicketCommandResult {
        val requester = customerDirectory.createUnverified(input.requesterName, input.requesterEmail)
        return ticketCommandService.create(
            CreateAgentTicketCommand(
                requesterId = requester.id,
                subject = input.subject,
                firstComment = input.firstComment,
                priority = input.priority,
                groupId = input.groupId,
                assigneeId = input.assigneeId,
                actor = principal.commandActor(),
                context = context,
            ),
        )
    }

    fun update(
        principal: StaffPrincipal,
        ticketNumber: Long,
        input: UpdateAgentTicketInput,
        context: CommandContext,
    ): TicketCommandResult = ticketCommandService.update(
        UpdateAgentTicketCommand(
            ticketNumber = ticketNumber,
            expectedVersion = input.expectedVersion,
            changedFields = input.changedFields,
            status = input.status,
            priority = input.priority,
            groupId = input.groupId,
            assigneeId = input.assigneeId,
            comment = input.comment,
            actor = principal.commandActor(),
            context = context,
        ),
    )

    fun transfer(
        principal: StaffPrincipal,
        ticketNumber: Long,
        input: TransferTicketInput,
        context: CommandContext,
    ): TicketCommandResult = ticketCommandService.transfer(
        TransferTicketCommand(
            ticketNumber = ticketNumber,
            expectedVersion = input.expectedVersion,
            groupId = input.groupId,
            assigneeId = input.assigneeId,
            reason = input.reason,
            actor = principal.commandActor(),
            context = context,
        ),
    )

    fun createChild(
        principal: StaffPrincipal,
        parentTicketNumber: Long,
        input: CreateChildTicketInput,
        context: CommandContext,
    ): CreateChildTicketResult = ticketCommandService.createChild(
        CreateChildTicketCommand(
            parentTicketNumber = parentTicketNumber,
            expectedVersion = input.expectedVersion,
            subject = input.subject,
            body = input.body,
            groupId = input.groupId,
            assigneeId = input.assigneeId,
            priority = input.priority,
            actor = principal.commandActor(),
            context = context,
        ),
    )

    fun createExternalReference(
        principal: StaffPrincipal,
        ticketNumber: Long,
        input: CreateTicketExternalReferenceInput,
        context: CommandContext,
    ): TicketExternalReferenceCommandResult = ticketCommandService.createExternalReference(
        CreateTicketExternalReferenceCommand(
            ticketNumber = ticketNumber,
            expectedVersion = input.expectedVersion,
            externalSystemId = input.externalSystemId,
            objectType = input.objectType,
            externalId = input.externalId,
            displayLabel = input.displayLabel,
            safeDeepLink = input.safeDeepLink,
            metadata = input.metadata,
            metadataObservedAt = input.metadataObservedAt,
            actor = principal.commandActor(),
            context = context,
        ),
    )

    fun deleteExternalReference(
        principal: StaffPrincipal,
        ticketNumber: Long,
        referenceId: UUID,
        expectedVersion: Long,
        context: CommandContext,
    ): TicketExternalReferenceCommandResult = ticketCommandService.deleteExternalReference(
        DeleteTicketExternalReferenceCommand(
            ticketNumber = ticketNumber,
            expectedVersion = expectedVersion,
            referenceId = referenceId,
            actor = principal.commandActor(),
            context = context,
        ),
    )

    private fun StaffPrincipal.commandActor() = StaffTicketCommandActor(
        id = id,
        displayName = displayName,
        isAdmin = role == StaffRole.ADMIN,
    )
}
