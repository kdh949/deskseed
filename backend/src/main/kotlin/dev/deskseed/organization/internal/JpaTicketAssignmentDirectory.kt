package dev.deskseed.organization.internal

import dev.deskseed.organization.OrganizationStatus
import dev.deskseed.organization.StaffStatus
import dev.deskseed.organization.TicketAssignmentDirectory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class JpaTicketAssignmentDirectory(
    private val groupRepository: SupportGroupRepository,
    private val membershipRepository: GroupMembershipRepository,
    private val staffRepository: StaffAccountRepository,
) : TicketAssignmentDirectory {
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
}
