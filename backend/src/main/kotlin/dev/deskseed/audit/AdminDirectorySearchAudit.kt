package dev.deskseed.audit

import java.time.Instant
import java.util.UUID

enum class AdminDirectorySearchKind(val action: String, val sort: String) {
    STAFF("ADMIN_STAFF_SEARCH_EXECUTED", "displayName:asc,id:asc"),
    GROUP("ADMIN_GROUP_SEARCH_EXECUTED", "name:asc,id:asc"),
}

data class AdminDirectorySearchScope(
    val page: Int,
    val size: Int,
    val role: String? = null,
    val status: String? = null,
    val memberOfGroupId: UUID? = null,
    val excludeGroupId: UUID? = null,
) {
    init {
        require(page >= 0 && size in 1..100)
        require(role == null || role in setOf("ADMIN", "AGENT", "SECURITY_AUDITOR"))
        require(status == null || status in setOf("ACTIVE", "DISABLED"))
        require(memberOfGroupId == null || excludeGroupId == null)
    }

    fun normalizedFilters(): Map<String, String> = buildMap {
        put("page", page.toString())
        put("size", size.toString())
        role?.let { put("role", it) }
        status?.let { put("status", it) }
        memberOfGroupId?.let { put("memberOfGroupId", it.toString()) }
        excludeGroupId?.let { put("excludeGroupId", it.toString()) }
    }
}

data class AdminDirectorySearchAccessAudit(
    val eventId: UUID,
    val context: AccessAuditContext,
    val interactionId: UUID,
    val kind: AdminDirectorySearchKind,
    val scope: AdminDirectorySearchScope,
    val protectedQuery: ProtectedSearchQueryAudit,
    val resultCount: Long,
    val occurredAt: Instant,
)
