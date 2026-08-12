package dev.deskseed.organization.internal

import dev.deskseed.organization.GrantableAuditAuthority
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

internal interface StaffAuthorityGrantRepository : JpaRepository<StaffAuthorityGrantEntity, UUID> {
    fun findAllByStaffIdOrderByAuthorityAsc(staffId: UUID): List<StaffAuthorityGrantEntity>

    fun findAllByStaffIdInOrderByStaffIdAscAuthorityAsc(
        staffIds: Collection<UUID>,
    ): List<StaffAuthorityGrantEntity>

    fun findByStaffIdAndAuthority(
        staffId: UUID,
        authority: GrantableAuditAuthority,
    ): StaffAuthorityGrantEntity?
}
