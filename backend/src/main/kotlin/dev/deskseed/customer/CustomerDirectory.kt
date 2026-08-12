package dev.deskseed.customer

import java.util.UUID

data class CustomerRef(
    val id: UUID,
    val name: String,
    val email: String,
)

interface CustomerDirectory {
    fun createUnverified(name: String, email: String): CustomerRef
}
