package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.macro.MacroActionType
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
import dev.deskseed.organization.StaffRole
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.CanonicalCommentContent
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.AgentTicketNotFoundException
import dev.deskseed.ticketing.ApplyMacroTicketCommand
import dev.deskseed.ticketing.StaffTicketCommandActor
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketField
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class MacroApplyApplicationService(
    private val macroAdministration: MacroDefinitionAdministration,
    private val ticketRead: AgentTicketReadApplicationService,
    private val ticketCommandService: AgentTicketCommandService,
) {
    fun apply(
        principal: StaffPrincipal,
        ticketNumber: Long,
        macroId: UUID,
        expectedTicketVersion: Long,
        macroVersion: Int,
        commentOverride: CanonicalCommentContent?,
        context: CommandContext,
    ): TicketCommandResult {
        ticketRead.requireReadableTicket(principal, ticketNumber)
        val actor = MacroDefinitionActor(
            staffId = principal.id,
            displayName = principal.displayName,
            isAdmin = principal.role == StaffRole.ADMIN,
            authorities = principal.authorities,
            source = RequestSource.AGENT_UI,
            requestId = context.requestId,
            correlationId = context.correlationId,
        )
        val macro = try {
            macroAdministration.getVersion(macroId, macroVersion, actor)
        } catch (_: MacroNotFoundException) {
            throw AgentTicketNotFoundException()
        }
        val commentAction = macro.actions.filterIsInstance<MacroCommentAction>().singleOrNull()
        if (commentAction == null && commentOverride != null) {
            throw IllegalArgumentException("comment override requires a COMMENT macro action")
        }
        if (commentAction != null && commentOverride == null) {
            throw IllegalArgumentException("comment override must carry the reviewed preview draft")
        }
        val comment = commentAction?.let { action ->
            val content = checkNotNull(commentOverride)
            AgentCommentDraft(
                visibility = action.visibility,
                body = content.body,
                contentFormat = content.format,
                contentDocument = content.document,
            )
        }
        val actionTypes = macro.actions.map { it.type.name }
        val changedFields = buildSet {
            if (MacroActionType.STATUS.name in actionTypes) add(TicketField.STATUS)
            if (MacroActionType.PRIORITY.name in actionTypes) add(TicketField.PRIORITY)
            if (MacroActionType.GROUP.name in actionTypes) add(TicketField.GROUP_ID)
            if (MacroActionType.ASSIGNEE.name in actionTypes) add(TicketField.ASSIGNEE_ID)
            if (actionTypes.any { it in CONFIGURATION_ACTION_TYPES }) add(TicketField.CONFIGURATION)
        }
        return ticketCommandService.applyMacro(
            ApplyMacroTicketCommand(
                ticketNumber = ticketNumber,
                expectedVersion = expectedTicketVersion,
                macroId = macro.id,
                macroVersion = macroVersion,
                orderedActionTypes = actionTypes,
                changedFields = changedFields,
                status = macro.actions.filterIsInstance<MacroStatusAction>().singleOrNull()?.status,
                priority = macro.actions.filterIsInstance<MacroPriorityAction>().singleOrNull()?.priority,
                groupId = macro.actions.filterIsInstance<MacroGroupAction>().singleOrNull()?.groupId,
                assigneeId = macro.actions.filterIsInstance<MacroAssigneeAction>().singleOrNull()?.assigneeId,
                comment = comment,
                formVersion = null,
                fieldValues = macro.actions.filterIsInstance<MacroCustomFieldAction>().associate { it.fieldKey to it.value },
                addTagIds = macro.actions.filterIsInstance<MacroAddTagAction>().mapTo(linkedSetOf(), MacroAddTagAction::tagId),
                removeTagIds = macro.actions.filterIsInstance<MacroRemoveTagAction>().mapTo(linkedSetOf(), MacroRemoveTagAction::tagId),
                customStatusId = macro.actions.filterIsInstance<MacroCustomStatusAction>().singleOrNull()?.customStatusId,
                actor = StaffTicketCommandActor(principal.id, principal.displayName, principal.role == StaffRole.ADMIN),
                context = context,
            ),
        )
    }

    private companion object {
        val CONFIGURATION_ACTION_TYPES = setOf("ADD_TAG", "REMOVE_TAG", "CUSTOM_FIELD", "CUSTOM_STATUS")
    }
}
