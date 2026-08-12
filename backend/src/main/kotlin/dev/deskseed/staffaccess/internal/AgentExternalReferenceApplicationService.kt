package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.integration.ExternalReferenceStore
import dev.deskseed.integration.ExternalReferenceView
import dev.deskseed.integration.ExternalSystemView
import dev.deskseed.ticketing.TicketExternalReferenceCommandResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

internal data class AgentExternalReferenceContext(
    val ticketVersion: Long,
    val canManage: Boolean,
    val availableSystems: List<ExternalSystemView>,
    val items: List<ExternalReferenceView>,
)

@Service
internal class AgentExternalReferenceApplicationService(
    private val ticketReadService: AgentTicketReadApplicationService,
    private val ticketCommandService: AgentTicketCommandApplicationService,
    private val externalReferenceStore: ExternalReferenceStore,
) {
    @Transactional
    fun list(
        principal: StaffPrincipal,
        ticketNumber: Long,
        interactionId: UUID,
        context: AgentReadRequestContext,
    ): AgentExternalReferenceContext {
        val workspace = ticketReadService.readTicket(
            principal = principal,
            ticketNumber = ticketNumber,
            interactionId = interactionId,
            intent = AgentReadIntent.BACKGROUND,
            originSearchEventId = null,
            context = context,
        )
        return AgentExternalReferenceContext(
            ticketVersion = workspace.detail.ticket.version,
            canManage = "UPDATE" in workspace.capabilities,
            availableSystems = externalReferenceStore.listActiveSystems(),
            items = externalReferenceStore.listForTicket(workspace.detail.ticket.id),
        )
    }

    fun create(
        principal: StaffPrincipal,
        ticketNumber: Long,
        input: CreateTicketExternalReferenceInput,
        context: CommandContext,
    ): TicketExternalReferenceCommandResult =
        ticketCommandService.createExternalReference(principal, ticketNumber, input, context)

    fun delete(
        principal: StaffPrincipal,
        ticketNumber: Long,
        referenceId: UUID,
        expectedVersion: Long,
        context: CommandContext,
    ): TicketExternalReferenceCommandResult =
        ticketCommandService.deleteExternalReference(principal, ticketNumber, referenceId, expectedVersion, context)
}
