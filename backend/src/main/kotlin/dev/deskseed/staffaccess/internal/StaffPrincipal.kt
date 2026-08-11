package dev.deskseed.staffaccess.internal

import dev.deskseed.organization.StaffIdentity
import dev.deskseed.organization.StaffRole
import java.io.Serializable
import java.util.UUID

internal data class StaffPrincipal(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: StaffRole,
) : Serializable {
    companion object {
        fun from(identity: StaffIdentity): StaffPrincipal = StaffPrincipal(
            id = identity.id,
            email = identity.email,
            displayName = identity.displayName,
            role = identity.role,
        )
    }
}
