package dev.deskseed.organization.internal

import dev.deskseed.organization.OrganizationStatus
import dev.deskseed.organization.StaffStatus
import dev.deskseed.organization.TicketAssignmentCatalog
import dev.deskseed.organization.TicketAssignmentDirectory
import dev.deskseed.organization.TicketAssignmentGroupOption
import dev.deskseed.organization.TicketAssignmentStaffOption
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class JpaTicketAssignmentDirectory(
    private val groupRepository: SupportGroupRepository,
    private val membershipRepository: GroupMembershipRepository,
    private val staffRepository: StaffAccountRepository,
) : TicketAssignmentDirectory, TicketAssignmentCatalog {
    @Transactional(readOnly = true)
    override fun isActiveGroup(groupId: UUID): Boolean = groupRepository.findById(groupId)
        .filter { it.status == OrganizationStatus.ACTIVE }
        .isPresent

    @Transactional(readOnly = true)
    override fun isActiveMember(groupId: UUID, staffId: UUID): Boolean {
        val activeGroup = groupRepository.findById(groupId)
            .filter { it.status == OrganizationStatus.ACTIVE }
            .isPresent
        val activeStaff = staffRepository.findById(staffId)
            .filter { it.status == StaffStatus.ACTIVE }
            .isPresent
        val activeMembership = membershipRepository.findByGroupIdAndStaffId(groupId, staffId)
            ?.status == GroupMembershipStatus.ACTIVE
        return activeGroup && activeStaff && activeMembership
    }

    @Transactional(readOnly = true)
    override fun listActiveGroups(): List<TicketAssignmentGroupOption> {
        val groups = groupRepository.findAllByStatusOrderByNameAscIdAsc(OrganizationStatus.ACTIVE)
        if (groups.isEmpty()) return emptyList()

        val memberships = membershipRepository.findAllByGroupIdInAndStatus(
            groups.map { it.id },
            GroupMembershipStatus.ACTIVE,
        )
        val staffById = staffRepository.findAllById(memberships.map { it.staffId }.toSet())
            .filter { it.status == StaffStatus.ACTIVE }
            .associateBy { it.id }
        val membersByGroup = memberships.groupBy { it.groupId }

        return groups.map { group ->
            TicketAssignmentGroupOption(
                id = group.id,
                name = group.name,
                members = membersByGroup[group.id].orEmpty()
                    .mapNotNull { membership ->
                        staffById[membership.staffId]?.let { staff ->
                            TicketAssignmentStaffOption(staff.id, staff.displayName)
                        }
                    }
                    .sortedWith(compareBy(TicketAssignmentStaffOption::displayName, TicketAssignmentStaffOption::id)),
            )
        }
    }
}
