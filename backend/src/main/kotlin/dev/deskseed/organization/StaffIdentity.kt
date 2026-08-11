package dev.deskseed.organization

import java.time.Instant
import java.util.UUID

enum class StaffRole {
    ADMIN,
    AGENT,
}

enum class StaffStatus {
    ACTIVE,
    DISABLED,
}

data class StaffIdentity(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: StaffRole,
    val status: StaffStatus,
)

interface StaffIdentityService {
    fun authenticate(email: String, password: String): StaffIdentity?

    fun findActiveById(id: UUID): StaffIdentity?

    fun recordSuccessfulLogin(id: UUID, occurredAt: Instant)
}
