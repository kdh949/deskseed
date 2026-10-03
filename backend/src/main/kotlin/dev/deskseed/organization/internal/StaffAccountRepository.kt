package dev.deskseed.organization.internal

import dev.deskseed.organization.StaffRole
import dev.deskseed.organization.StaffStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

internal interface StaffAccountRepository : JpaRepository<StaffAccountEntity, UUID>, JpaSpecificationExecutor<StaffAccountEntity> {
    fun findByEmailNormalized(emailNormalized: String): StaffAccountEntity?

    fun countByRoleAndStatus(role: StaffRole, status: StaffStatus): Long

}
