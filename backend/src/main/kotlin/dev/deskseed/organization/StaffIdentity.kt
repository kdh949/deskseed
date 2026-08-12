package dev.deskseed.organization

import java.time.Instant
import java.util.UUID

enum class StaffRole {
    ADMIN,
    AGENT,
    SECURITY_AUDITOR,
}

enum class GrantableAuditAuthority(val capability: String) {
    AUDIT_SEARCH_QUERY_REVEAL("audit:search-query:reveal"),
    AUDIT_EXPORT("audit:export"),
    AUDIT_PROJECTION_REBUILD("audit:projection:rebuild"),
}

object StaffAuthorityCatalog {
    const val AGENT_WORKSPACE = "AGENT_WORKSPACE"
    const val ADMIN_MANAGE = "ADMIN_MANAGE"
    const val INTEGRATION_CLIENT_MANAGE = "integration:clients:manage"
    const val EXTERNAL_SYSTEM_MANAGE = "integration:systems:manage"
    const val AUDIT_ACTIVITY_READ = "audit:activity:read"
    const val AUDIT_TICKET_CHANGE_READ = "audit:ticket-change:read"
    const val AUDIT_ACCESS_READ = "audit:access:read"
    const val AUDIT_ADMIN_SECURITY_READ = "audit:admin-security:read"
    const val AUDIT_SEARCH_QUERY_REVEAL = "audit:search-query:reveal"
    const val AUDIT_EXPORT = "audit:export"
    const val AUDIT_PROJECTION_REBUILD = "audit:projection:rebuild"

    fun forRole(role: StaffRole): Set<String> = when (role) {
        StaffRole.ADMIN -> setOf(
            ADMIN_MANAGE,
            AGENT_WORKSPACE,
            INTEGRATION_CLIENT_MANAGE,
            EXTERNAL_SYSTEM_MANAGE,
        )
        StaffRole.AGENT -> setOf(AGENT_WORKSPACE)
        StaffRole.SECURITY_AUDITOR -> setOf(
            AUDIT_ACTIVITY_READ,
            AUDIT_TICKET_CHANGE_READ,
            AUDIT_ACCESS_READ,
            AUDIT_ADMIN_SECURITY_READ,
        )
    }

    fun forIdentity(
        role: StaffRole,
        grantedAuditAuthorities: Set<GrantableAuditAuthority>,
    ): Set<String> = forRole(role) + if (role == StaffRole.SECURITY_AUDITOR) {
        grantedAuditAuthorities.map(GrantableAuditAuthority::capability)
    } else {
        emptyList()
    }
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
    val authorities: Set<String> = StaffAuthorityCatalog.forRole(role),
)

interface StaffIdentityService {
    fun authenticate(email: String, password: String): StaffIdentity?

    fun findActiveById(id: UUID): StaffIdentity?

    fun recordSuccessfulLogin(id: UUID, occurredAt: Instant)
}
