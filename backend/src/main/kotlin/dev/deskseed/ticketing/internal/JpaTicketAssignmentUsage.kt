package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.TicketAssignmentUsage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class JpaTicketAssignmentUsage(
    private val repository: TicketRepository,
) : TicketAssignmentUsage {
    @Transactional(readOnly = true)
    override fun hasTicketsAssignedToStaff(staffId: UUID): Boolean =
        repository.existsByAssigneeId(staffId)

    @Transactional(readOnly = true)
    override fun hasTicketsInGroup(groupId: UUID): Boolean = repository.existsByGroupId(groupId)

    @Transactional(readOnly = true)
    override fun hasTicketsAssignedToMember(groupId: UUID, staffId: UUID): Boolean =
        repository.existsByGroupIdAndAssigneeId(groupId, staffId)
}
