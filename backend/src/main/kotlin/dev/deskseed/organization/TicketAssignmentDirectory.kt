package dev.deskseed.organization

import java.util.UUID

interface TicketAssignmentDirectory {
    fun isActiveGroup(groupId: UUID): Boolean

    fun isActiveMember(groupId: UUID, staffId: UUID): Boolean
}
