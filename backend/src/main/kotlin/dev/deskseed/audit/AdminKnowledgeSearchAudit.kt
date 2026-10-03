package dev.deskseed.audit

import java.time.Instant
import java.util.UUID

const val ADMIN_KNOWLEDGE_SEARCH_ACTION = "ADMIN_KNOWLEDGE_SEARCH_EXECUTED"

data class AdminKnowledgeSearchScope(
    val lifecycle: String?,
    val sectionId: UUID?,
    val audience: String?,
    val cursorPresent: Boolean,
) {
    init {
        require(lifecycle == null || lifecycle in setOf("DRAFT", "IN_REVIEW", "PUBLISHED", "UNPUBLISHED", "ARCHIVED"))
        require(audience == null || audience in setOf("PUBLIC", "SIGNED_IN_CUSTOMER", "STAFF", "SELECTED_STAFF_GROUPS"))
    }

    fun normalizedFilters(): Map<String, String> = buildMap {
        lifecycle?.let { put("lifecycle", it) }
        sectionId?.let { put("sectionId", it.toString()) }
        audience?.let { put("audience", it) }
        put("cursorPresent", cursorPresent.toString())
        put("pageSize", "50")
    }
}

data class AdminKnowledgeSearchAccessAudit(
    val eventId: UUID,
    val context: AccessAuditContext,
    val interactionId: UUID,
    val scope: AdminKnowledgeSearchScope,
    val protectedQuery: ProtectedSearchQueryAudit,
    val resultCount: Long,
    val occurredAt: Instant,
)
