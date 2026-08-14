package dev.deskseed.ticketing

import java.time.Instant
import java.util.UUID

enum class StaffTicketReadScope {
    ALL_TICKETS,
    OWN_GROUPS,
    ASSIGNED_ONLY,
    EXPLICIT_GROUP_MATRIX,
}

enum class DefaultStaffView(val key: String, val displayName: String, val category: String) {
    MY_OPEN("my-open", "내 open", "내 작업"),
    UNASSIGNED_MY_GROUPS("unassigned-my-groups", "내 그룹 미배정", "내 작업"),
    PENDING("pending", "Pending", "공유"),
    RECENTLY_SOLVED("recently-solved", "최근 solved", "최근"),
    MY_CHILD_TASKS("my-child-tasks", "내 child tasks", "내 작업");

    companion object {
        fun fromKey(key: String): DefaultStaffView? = entries.firstOrNull { it.key == key }
    }
}

data class StaffTicketListFilter(
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val groupId: UUID? = null,
    val assignee: String? = null,
    val slaState: StaffSlaDisplayState? = null,
)

data class StaffTicketSearchFilter(
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val groupId: UUID? = null,
    val assignee: String? = null,
)

data class StaffTicketSearchResult(
    val items: List<StaffTicketSummary>,
    val resultCount: Long,
)

data class StaffTicketCursor(
    val updatedAt: Instant,
    val ticketNumber: Long,
)

data class StaffActorSummary(
    val id: UUID?,
    val type: String,
    val displayName: String,
)

data class StaffReference(val id: UUID, val displayName: String)

data class StaffGroupReference(val id: UUID, val name: String)

data class StaffTicketSummary(
    val id: UUID,
    val ticketNumber: Long,
    val subject: String,
    val status: TicketStatus,
    val priority: TicketPriority,
    val requester: StaffActorSummary,
    val group: StaffGroupReference?,
    val assignee: StaffReference?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
    val isChild: Boolean,
    val openChildCount: Int = 0,
    val sla: StaffSlaBadge? = null,
)

enum class StaffSlaDisplayState {
    ACTIVE,
    AT_RISK,
    PAUSED,
    ACHIEVED,
    BREACHED,
    CANCELLED,
    NO_POLICY,
}

data class StaffSlaBadge(
    val metric: String = "FIRST_REPLY",
    val state: StaffSlaDisplayState,
    val dueAt: Instant?,
    val targetMinutes: Long?,
    val policyVersion: Int?,
    val scheduleVersion: Int?,
)

data class StaffCommentView(
    val id: UUID,
    val visibility: CommentVisibility,
    val actor: StaffActorSummary,
    val body: String,
    val createdAt: Instant,
    val source: String,
)

data class StaffTicketCustomer(
    val id: UUID,
    val displayName: String,
    val email: String,
)

data class StaffTicketHistoryItem(
    val id: UUID,
    val eventType: String,
    val actor: StaffActorSummary,
    val occurredAt: Instant,
)

data class StaffTicketDetail(
    val ticket: StaffTicketSummary,
    val comments: List<StaffCommentView>,
    val customer: StaffTicketCustomer?,
    val history: List<StaffTicketHistoryItem>,
    val parent: StaffTicketSummary?,
    val children: List<StaffTicketSummary>,
)

interface StaffTicketReadStore {
    fun list(
        view: DefaultStaffView,
        actorId: UUID,
        filters: StaffTicketListFilter,
        cursor: StaffTicketCursor?,
        limit: Int,
        recentlySolvedAfter: Instant,
    ): List<StaffTicketSummary>

    fun findDetail(ticketNumber: Long): StaffTicketDetail?

    fun search(
        query: String,
        scope: StaffTicketReadScope,
        actorId: UUID,
        filters: StaffTicketSearchFilter,
        limit: Int,
    ): StaffTicketSearchResult

    fun hasRelationReadGrant(ticketId: UUID, actorId: UUID): Boolean
}
