package dev.deskseed.staffaccess.internal

import dev.deskseed.organization.TicketAssignmentDirectory
import dev.deskseed.ticketing.StaffTicketCommandActor
import dev.deskseed.ticketing.TicketAssignmentPolicy
import dev.deskseed.ticketing.TicketWriteAuthorizationPolicy
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class GroupOrAssigneeTicketWriteAuthorizationPolicy(
    private val assignmentDirectory: TicketAssignmentDirectory,
) : TicketWriteAuthorizationPolicy, TicketAssignmentPolicy {
    override fun canUpdate(
        actor: StaffTicketCommandActor,
        currentGroupId: UUID?,
        currentAssigneeId: UUID?,
    ): Boolean {
        if (actor.isAdmin || currentAssigneeId == actor.id) return true
        return currentGroupId != null && isActiveMember(currentGroupId, actor.id)
    }

    override fun isActiveGroup(groupId: UUID): Boolean = assignmentDirectory.isActiveGroup(groupId)

    override fun isActiveMember(groupId: UUID, staffId: UUID): Boolean =
        assignmentDirectory.isActiveMember(groupId, staffId)
}
