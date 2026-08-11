package dev.deskseed.organization.internal

import dev.deskseed.organization.StaffRole
import dev.deskseed.organization.StaffStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

internal interface StaffAccountRepository : JpaRepository<StaffAccountEntity, UUID> {
    fun findByEmailNormalized(emailNormalized: String): StaffAccountEntity?

    fun countByRoleAndStatus(role: StaffRole, status: StaffStatus): Long

    fun findAllByOrderByDisplayNameAscIdAsc(): List<StaffAccountEntity>
}
