package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.ClosedTicketDraftException
import dev.deskseed.collaboration.NewTicketDraft
import dev.deskseed.collaboration.TicketDraft
import dev.deskseed.collaboration.TicketDraftChannel
import dev.deskseed.collaboration.TicketDraftConflictException
import dev.deskseed.collaboration.TicketDraftStore
import dev.deskseed.collaboration.UpdatedTicketDraft
import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.TicketDraftAttachmentReferenceValidator
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.CommentContentFormat
import tools.jackson.databind.JsonNode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class AgentTicketDraftApplicationService(
    private val ticketReadApplicationService: AgentTicketReadApplicationService,
    private val drafts: TicketDraftStore,
    private val attachmentReferences: TicketDraftAttachmentReferenceValidator,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun read(
        principal: StaffPrincipal,
        ticketNumber: Long,
        channel: TicketDraftChannel,
    ): TicketDraft {
        val ticket = ticketReadApplicationService.requireReadableTicket(principal, ticketNumber).ticket
        return drafts.find(principal.id, ticket.id, channel) ?: throw AgentTicketDraftNotFoundException()
    }

    @Transactional
    fun save(
        principal: StaffPrincipal,
        ticketNumber: Long,
        channel: TicketDraftChannel,
        request: SaveAgentTicketDraft,
        @Suppress("UNUSED_PARAMETER") context: CommandContext,
    ): TicketDraft {
        val ticket = ticketReadApplicationService.requireReadableTicket(principal, ticketNumber).ticket
        if (ticket.status == TicketStatus.CLOSED) throw ClosedTicketDraftException()
        attachmentReferences.validateDraftReferences(
            ticketId = ticket.id,
            actor = ActorRef(ActorType.STAFF, principal.id),
            visibility = channel.toAttachmentVisibility(),
            attachmentIds = request.attachmentIds.toSet(),
            now = Instant.now(clock),
        )
        val saved = if (request.expectedDraftVersion == 0L) {
            drafts.create(
                NewTicketDraft(
                    ownerStaffId = principal.id,
                    ticketId = ticket.id,
                    ticketNumber = ticket.ticketNumber,
                    channel = channel,
                    body = request.body,
                    attachmentIds = request.attachmentIds,
                    clientDeviceId = request.clientDeviceId,
                    baseTicketVersion = request.baseTicketVersion,
                    contentFormat = request.contentFormat,
                    contentDocument = request.contentDocument,
                ),
            )
        } else {
            drafts.update(
                principal.id,
                ticket.id,
                channel,
                UpdatedTicketDraft(
                    body = request.body,
                    attachmentIds = request.attachmentIds,
                    clientDeviceId = request.clientDeviceId,
                    baseTicketVersion = request.baseTicketVersion,
                    expectedDraftVersion = request.expectedDraftVersion,
                    contentFormat = request.contentFormat,
                    contentDocument = request.contentDocument,
                ),
            )
        }
        if (saved != null) return saved
        val current = drafts.find(principal.id, ticket.id, channel)
        if (current == null) throw AgentTicketDraftNotFoundException()
        throw TicketDraftConflictException(current)
    }

    @Transactional
    fun clear(
        principal: StaffPrincipal,
        ticketNumber: Long,
        channel: TicketDraftChannel,
        expectedDraftVersion: Long,
        @Suppress("UNUSED_PARAMETER") context: CommandContext,
    ) {
        val ticket = ticketReadApplicationService.requireReadableTicket(principal, ticketNumber).ticket
        if (drafts.find(principal.id, ticket.id, channel) == null) throw AgentTicketDraftNotFoundException()
        if (!drafts.delete(principal.id, ticket.id, channel, expectedDraftVersion)) {
            val current = drafts.find(principal.id, ticket.id, channel)
                ?: throw AgentTicketDraftNotFoundException()
            throw TicketDraftConflictException(current)
        }
    }

    @Transactional(readOnly = true)
    fun listRecoverable(principal: StaffPrincipal, limit: Int): List<TicketDraft> =
        drafts.listRecoverable(principal.id, limit).filter { draft ->
            try {
                ticketReadApplicationService.requireReadableTicket(principal, draft.ticketNumber)
                true
            } catch (_: AgentTicketNotFoundException) {
                false
            }
        }
}

private fun TicketDraftChannel.toAttachmentVisibility() = when (this) {
    TicketDraftChannel.PUBLIC_REPLY -> AttachmentVisibility.PUBLIC
    TicketDraftChannel.INTERNAL_NOTE -> AttachmentVisibility.INTERNAL
}

internal data class SaveAgentTicketDraft(
    val body: String,
    val attachmentIds: List<UUID>,
    val clientDeviceId: UUID,
    val baseTicketVersion: Long,
    val expectedDraftVersion: Long,
    val contentFormat: CommentContentFormat = CommentContentFormat.PLAIN_TEXT,
    val contentDocument: JsonNode? = null,
)

internal class AgentTicketDraftNotFoundException : RuntimeException()
