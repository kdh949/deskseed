package dev.deskseed.customer

import java.util.UUID
import java.time.Instant

data class CustomerRef(
    val id: UUID,
    val name: String,
    val email: String,
    val verifiedAt: Instant? = null,
)

interface CustomerDirectory {
    fun createUnverified(name: String, email: String): CustomerRef

    fun findById(customerId: UUID): CustomerRef?

    fun existsByNormalizedEmail(email: String): Boolean

    fun findVerifiedByNormalizedEmail(email: String): CustomerRef?

    /** Substring match over name/email, ordered by name, for staff requester lookup. */
    fun search(query: String, limit: Int): List<CustomerRef>

    /** Creates a new verified identity. It intentionally never upgrades an anonymous historical requester. */
    fun createVerified(name: String, email: String, verifiedAt: Instant): CustomerRef
}
