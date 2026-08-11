package dev.deskseed.organization

import java.util.UUID

data class TicketAssignmentStaffOption(
    val id: UUID,
    val displayName: String,
)

data class TicketAssignmentGroupOption(
    val id: UUID,
    val name: String,
    val members: List<TicketAssignmentStaffOption>,
)

interface TicketAssignmentCatalog {
    fun listActiveGroups(): List<TicketAssignmentGroupOption>
}
