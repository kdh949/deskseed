package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditOutcome
import dev.deskseed.audit.AccessAuditProtectionException
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AccessAuditWriter
import dev.deskseed.audit.MacroPreviewedAccessAudit
import dev.deskseed.foundation.RequestSource
import dev.deskseed.macro.MacroAddTagAction
import dev.deskseed.macro.MacroAssigneeAction
import dev.deskseed.macro.MacroCommentAction
import dev.deskseed.macro.MacroCustomFieldAction
import dev.deskseed.macro.MacroCustomStatusAction
import dev.deskseed.macro.MacroDefinitionActor
import dev.deskseed.macro.MacroDefinitionAdministration
import dev.deskseed.macro.MacroGroupAction
import dev.deskseed.macro.MacroNotFoundException
import dev.deskseed.macro.MacroPriorityAction
import dev.deskseed.macro.MacroRemoveTagAction
import dev.deskseed.macro.MacroStatusAction
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeQuery
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeValue
import dev.deskseed.ticketing.AgentTicketNotFoundException
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketAssignmentPolicy
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.TicketConfigurationMutationHandler
import dev.deskseed.ticketing.TicketConfigurationMutationRequest
import dev.deskseed.ticketing.TicketMacroContextQuery
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketStatusTransitionPolicy
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class MacroPreviewChange(
    val field: String,
    val before: String?,
    val after: String?,
)

internal data class MacroPreviewComment(
    val visibility: CommentVisibility,
    val body: String,
)

internal data class MacroPreviewResult(
    val macroId: UUID,
    val macroVersion: Int,
    val ticketNumber: Long,
    val ticketVersion: Long,
    val changes: List<MacroPreviewChange>,
    val comment: MacroPreviewComment?,
)

@Service
internal class MacroPreviewApplicationService(
    private val macroAdministration: MacroDefinitionAdministration,
    private val ticketRead: AgentTicketReadApplicationService,
    private val ticketContextQuery: TicketMacroContextQuery,
    private val configurationQuery: TicketConfigurationRuntimeQuery,
    private val configurationMutationHandler: TicketConfigurationMutationHandler,
    private val assignmentPolicy: TicketAssignmentPolicy,
    private val accessAuditWriter: AccessAuditWriter,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
    private val clock: Clock,
) {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    fun preview(
        principal: StaffPrincipal,
        ticketNumber: Long,
        macroId: UUID,
        interactionId: UUID,
        context: AgentReadRequestContext,
    ): MacroPreviewResult {
        val detail = ticketRead.requireReadableTicket(principal, ticketNumber)
        val ticket = ticketContextQuery.find(ticketNumber) ?: throw AgentTicketNotFoundException()
        if (ticket.ticketId != detail.ticket.id || ticket.version != detail.ticket.version) {
            throw IllegalStateException("Ticket projection changed while preparing macro preview")
        }
        if (ticket.status == TicketStatus.CLOSED) throw IllegalArgumentException("Closed tickets cannot preview a write macro")
        val actor = MacroDefinitionActor(
            staffId = principal.id,
            displayName = principal.displayName,
            isAdmin = principal.role == dev.deskseed.organization.StaffRole.ADMIN,
            authorities = principal.authorities,
            source = RequestSource.AGENT_UI,
            requestId = context.requestId,
            correlationId = context.correlationId,
        )
        val macro = try {
            macroAdministration.getActive(macroId, actor)
        } catch (_: MacroNotFoundException) {
            throw AgentTicketNotFoundException()
        }
        val macroVersion = checkNotNull(macro.activeVersion)
        validateAssignmentActions(
            group = macro.actions.filterIsInstance<MacroGroupAction>().singleOrNull(),
            assignee = macro.actions.filterIsInstance<MacroAssigneeAction>().singleOrNull(),
            currentGroupId = detail.detailGroupId(),
            currentAssigneeId = detail.ticket.assignee?.id,
        )

        val fieldValues = macro.actions.filterIsInstance<MacroCustomFieldAction>()
            .associate { it.fieldKey to it.value }
        val addTagIds = macro.actions.filterIsInstance<MacroAddTagAction>().mapTo(linkedSetOf(), MacroAddTagAction::tagId)
        val removeTagIds = macro.actions.filterIsInstance<MacroRemoveTagAction>().mapTo(linkedSetOf(), MacroRemoveTagAction::tagId)
        val customStatusId = macro.actions.filterIsInstance<MacroCustomStatusAction>().singleOrNull()?.customStatusId
        val targetStatus = macro.actions.filterIsInstance<MacroStatusAction>().singleOrNull()?.status ?: ticket.status
        TicketStatusTransitionPolicy.requireStaffTransition(ticket.status, targetStatus)
        configurationMutationHandler.validate(
            TicketConfigurationMutationRequest(
                ticketId = ticket.ticketId,
                ticketNumber = ticket.ticketNumber,
                ticketKind = ticket.ticketKind,
                currentStatus = targetStatus,
                formVersion = null,
                fieldValues = fieldValues,
                addTagIds = addTagIds,
                removeTagIds = removeTagIds,
                customStatusId = customStatusId,
                occurredAt = Instant.now(clock),
            ),
        )
        val configuration = configurationQuery.readAgentConfiguration(
            ticket.ticketId,
            ticket.ticketNumber,
            ticket.version,
            ticket.status,
        )
        val changes = macro.actions.mapNotNull { action ->
            when (action) {
                is MacroStatusAction -> MacroPreviewChange("status", ticket.status.name, action.status.name)
                is MacroPriorityAction -> MacroPreviewChange("priority", detail.ticket.priority.name, action.priority.name)
                is MacroGroupAction -> MacroPreviewChange("groupId", detail.ticket.group?.id?.toString(), action.groupId.toString())
                is MacroAssigneeAction -> MacroPreviewChange("assigneeId", detail.ticket.assignee?.id?.toString(), action.assigneeId?.toString())
                is MacroAddTagAction -> MacroPreviewChange(
                    "tag.${action.tagId}",
                    if (configuration.tags.any { it.id == action.tagId }) "PRESENT" else "ABSENT",
                    "PRESENT",
                )
                is MacroRemoveTagAction -> MacroPreviewChange(
                    "tag.${action.tagId}",
                    if (configuration.tags.any { it.id == action.tagId }) "PRESENT" else "ABSENT",
                    "ABSENT",
                )
                is MacroCustomFieldAction -> MacroPreviewChange(
                    "field.${action.fieldKey}",
                    configuration.fieldValues[action.fieldKey]?.previewValue(),
                    action.value.previewValue(),
                )
                is MacroCustomStatusAction -> MacroPreviewChange(
                    "customStatusId",
                    configuration.customStatus?.id?.toString(),
                    action.customStatusId.toString(),
                )
                is MacroCommentAction -> null
            }
        }
        val comment = macro.actions.filterIsInstance<MacroCommentAction>().singleOrNull()?.let { action ->
            MacroPreviewComment(
                action.visibility,
                renderTemplate(
                    action.template,
                    mapOf(
                        "ticket.number" to ticket.ticketNumber.toString(),
                        "ticket.subject" to detail.ticket.subject,
                        "ticket.status" to ticket.status.name,
                        "ticket.priority" to detail.ticket.priority.name,
                        "requester.name" to detail.ticket.requester.displayName,
                        "agent.name" to principal.displayName,
                    ),
                ),
            )
        }
        try {
            accessAuditWriter.appendMacroPreviewed(
                MacroPreviewedAccessAudit(
                    eventId = UUID.randomUUID(),
                    context = context.toAccessAuditContext(principal, sessionFingerprint.fingerprint(context.sessionId)),
                    macroId = macro.id,
                    macroVersion = macroVersion,
                    ticketId = ticket.ticketId,
                    ticketNumber = ticket.ticketNumber,
                    ticketVersion = ticket.version,
                    interactionId = interactionId,
                    outcome = AccessAuditOutcome.SUCCEEDED,
                    httpStatus = 200,
                    occurredAt = Instant.now(clock),
                ),
            )
        } catch (failure: DataAccessException) {
            throw AccessAuditUnavailableException(failure)
        } catch (failure: AccessAuditProtectionException) {
            throw AccessAuditUnavailableException(failure)
        }
        return MacroPreviewResult(macro.id, macroVersion, ticket.ticketNumber, ticket.version, changes, comment)
    }

    private fun validateAssignmentActions(
        group: MacroGroupAction?,
        assignee: MacroAssigneeAction?,
        currentGroupId: UUID?,
        currentAssigneeId: UUID?,
    ) {
        val targetGroupId = group?.groupId ?: currentGroupId
        val targetAssigneeId = assignee?.assigneeId ?: currentAssigneeId
        assignmentPolicy.requireValidChange(
            currentAssigneeId = currentAssigneeId,
            targetGroupId = targetGroupId,
            targetAssigneeId = targetAssigneeId,
            groupRequested = group != null,
            assigneeRequested = assignee != null,
        )
    }

    private fun dev.deskseed.ticketing.StaffTicketDetail.detailGroupId(): UUID? = ticket.group?.id

    private fun renderTemplate(template: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(template) { match -> values.getValue(match.groupValues[1]) }

    private fun TicketConfigurationRuntimeValue.previewValue(): String? = booleanValue?.toString()
        ?: numberValue?.toPlainString()
        ?: optionId?.toString()
        ?: shortTextValue
        ?: longTextValue

    private fun TicketConfigurationFieldValue.previewValue(): String = booleanValue?.toString()
        ?: numberValue
        ?: optionId?.toString()
        ?: shortTextValue
        ?: checkNotNull(longTextValue)

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{([a-z]+(?:\\.[a-z]+)+)}}")
    }
}
