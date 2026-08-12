package dev.deskseed.customer.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

internal interface CustomerRepository : JpaRepository<CustomerEntity, UUID> {
    fun existsByEmailNormalized(emailNormalized: String): Boolean

    fun findFirstByEmailNormalizedAndVerifiedAtIsNotNull(emailNormalized: String): CustomerEntity?
}
