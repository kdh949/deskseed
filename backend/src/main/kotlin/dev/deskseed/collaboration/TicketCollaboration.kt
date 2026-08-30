package dev.deskseed.collaboration

import java.time.Instant
import java.util.UUID

data class CollaborationStaffSummary(
    val id: UUID,
    val displayName: String,
)

data class TicketCollaborationNote(
    val id: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val author: CollaborationStaffSummary,
    val body: String,
    val mentionedStaff: List<CollaborationStaffSummary>,
    val auditId: UUID,
    val createdAt: Instant,
)

data class NewTicketCollaborationNote(
    val id: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val author: CollaborationStaffSummary,
    val body: String,
    val mentionedStaff: List<CollaborationStaffSummary>,
    val clientCommandId: UUID,
    val commandFingerprint: String,
    val auditId: UUID,
    val createdAt: Instant,
    val notificationIds: Map<UUID, UUID>,
)

data class CollaborationCommandReplay(
    val note: TicketCollaborationNote,
    val commandFingerprint: String,
)

data class CollaborationCursor(val createdAt: Instant, val id: UUID)

data class CollaborationNotePage(
    val items: List<TicketCollaborationNote>,
    val nextCursor: CollaborationCursor?,
)

enum class AgentNotificationType {
    COLLABORATION_MENTION,
}

data class AgentNotification(
    val id: UUID,
    val recipientStaffId: UUID,
    val type: AgentNotificationType,
    val ticketNumber: Long,
    val noteId: UUID,
    val actor: CollaborationStaffSummary,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class AgentNotificationPage(
    val items: List<AgentNotification>,
    val unreadCount: Int,
    val nextCursor: CollaborationCursor?,
)

interface TicketCollaborationStore {
    fun lockCommand(actorStaffId: UUID, clientCommandId: UUID)

    fun findCommand(actorStaffId: UUID, clientCommandId: UUID): CollaborationCommandReplay?

    fun append(note: NewTicketCollaborationNote): TicketCollaborationNote

    fun listNotes(ticketId: UUID, before: CollaborationCursor?, limit: Int): CollaborationNotePage

    fun listNotifications(recipientStaffId: UUID, before: CollaborationCursor?, limit: Int): AgentNotificationPage

    fun markNotificationRead(recipientStaffId: UUID, notificationId: UUID, readAt: Instant): Boolean
}

data class StaffNotificationCreated(
    val recipientStaffId: UUID,
    val notificationId: UUID,
    val occurredAt: Instant,
)
