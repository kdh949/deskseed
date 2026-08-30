package dev.deskseed.collaboration

import dev.deskseed.ticketing.CommentContentFormat
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/** The draft channel is deliberately independent from persisted ticket comments. */
enum class TicketDraftChannel {
    PUBLIC_REPLY,
    INTERNAL_NOTE,
}

/** Owner-only recoverable composer state. It is not a TicketAudit or ticket mutation. */
data class TicketDraft(
    val ownerStaffId: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val channel: TicketDraftChannel,
    val body: String,
    val attachmentIds: List<UUID>,
    val clientDeviceId: UUID,
    val baseTicketVersion: Long,
    val draftVersion: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val expiresAt: Instant,
    val contentFormat: CommentContentFormat = CommentContentFormat.PLAIN_TEXT,
    val contentDocument: JsonNode? = null,
)

data class NewTicketDraft(
    val ownerStaffId: UUID,
    val ticketId: UUID,
    val ticketNumber: Long,
    val channel: TicketDraftChannel,
    val body: String,
    val attachmentIds: List<UUID>,
    val clientDeviceId: UUID,
    val baseTicketVersion: Long,
    val contentFormat: CommentContentFormat = CommentContentFormat.PLAIN_TEXT,
    val contentDocument: JsonNode? = null,
)

data class UpdatedTicketDraft(
    val body: String,
    val attachmentIds: List<UUID>,
    val clientDeviceId: UUID,
    val baseTicketVersion: Long,
    val expectedDraftVersion: Long,
    val contentFormat: CommentContentFormat = CommentContentFormat.PLAIN_TEXT,
    val contentDocument: JsonNode? = null,
)

interface TicketDraftStore {
    fun find(ownerStaffId: UUID, ticketId: UUID, channel: TicketDraftChannel): TicketDraft?

    fun create(draft: NewTicketDraft): TicketDraft?

    fun update(
        ownerStaffId: UUID,
        ticketId: UUID,
        channel: TicketDraftChannel,
        draft: UpdatedTicketDraft,
    ): TicketDraft?

    fun delete(ownerStaffId: UUID, ticketId: UUID, channel: TicketDraftChannel, expectedDraftVersion: Long): Boolean

    fun listRecoverable(ownerStaffId: UUID, limit: Int): List<TicketDraft>
}

interface TicketDraftMaintenance {
    /** Deletes a bounded set of expired drafts after holding the singleton cleanup lease. */
    fun purgeExpired(workerId: String, limit: Int): Int
}

class TicketDraftConflictException(
    val current: TicketDraft?,
) : RuntimeException()

class ClosedTicketDraftException : RuntimeException()
