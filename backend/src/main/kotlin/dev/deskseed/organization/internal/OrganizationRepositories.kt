package dev.deskseed.organization.internal

import dev.deskseed.organization.OrganizationStatus

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

internal interface SupportGroupRepository : JpaRepository<SupportGroupEntity, UUID> {
    fun findByNameIgnoreCase(name: String): SupportGroupEntity?

    fun findAllByOrderByNameAscIdAsc(): List<SupportGroupEntity>

    fun findAllByStatusOrderByNameAscIdAsc(status: OrganizationStatus): List<SupportGroupEntity>
}

internal interface GroupMembershipRepository : JpaRepository<GroupMembershipEntity, UUID> {
    fun findByGroupIdAndStaffId(groupId: UUID, staffId: UUID): GroupMembershipEntity?

    fun findAllByGroupIdAndStatusOrderByStaffIdAsc(
        groupId: UUID,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>

    fun findAllByStaffIdAndStatusOrderByGroupIdAsc(
        staffId: UUID,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>

    fun countByGroupIdAndStatus(groupId: UUID, status: GroupMembershipStatus): Long

    fun findAllByGroupIdInAndStatus(
        groupIds: Collection<UUID>,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>
}
