package dev.deskseed.organization.internal

import dev.deskseed.organization.OrganizationStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

internal interface SupportGroupRepository : JpaRepository<SupportGroupEntity, UUID> {
    fun findByNameIgnoreCase(name: String): SupportGroupEntity?

    fun findAllByStatusOrderByNameAscIdAsc(status: OrganizationStatus): List<SupportGroupEntity>
}

internal interface GroupMemberCountRow {
    val groupId: UUID
    val memberCount: Long
}

internal interface GroupMembershipRepository : JpaRepository<GroupMembershipEntity, UUID> {
    fun findByGroupIdAndStaffId(groupId: UUID, staffId: UUID): GroupMembershipEntity?

    fun findAllByGroupIdAndStatusOrderByStaffIdAsc(
        groupId: UUID,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>

    fun findAllByGroupIdAndStatus(
        groupId: UUID,
        status: GroupMembershipStatus,
        pageable: Pageable,
    ): Page<GroupMembershipEntity>

    fun findAllByStaffIdAndStatusOrderByGroupIdAsc(
        staffId: UUID,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>

    fun findAllByStaffIdInAndStatus(
        staffIds: Collection<UUID>,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>

    fun countByGroupIdAndStatus(groupId: UUID, status: GroupMembershipStatus): Long

    fun findAllByGroupIdInAndStatus(
        groupIds: Collection<UUID>,
        status: GroupMembershipStatus,
    ): List<GroupMembershipEntity>

    @Query(
        """
        select membership.groupId as groupId, count(membership.id) as memberCount
        from GroupMembershipEntity membership
        where membership.groupId in :groupIds and membership.status = :status
        group by membership.groupId
        """,
    )
    fun countActiveMembersByGroupIds(
        @Param("groupIds") groupIds: Collection<UUID>,
        @Param("status") status: GroupMembershipStatus,
    ): List<GroupMemberCountRow>
}
