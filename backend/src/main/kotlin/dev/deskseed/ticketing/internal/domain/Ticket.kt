package dev.deskseed.ticketing.internal.domain

import dev.deskseed.ticketing.CommentAuthorType
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import java.time.Instant
import java.util.UUID

internal class Ticket private constructor(
    val id: UUID,
    val ticketNumber: Long,
    val requesterId: UUID,
    val kind: TicketKind,
    val subject: String,
    val status: TicketStatus,
    val priority: TicketPriority,
    val channel: TicketChannel,
    val createdAt: Instant,
    val updatedAt: Instant,
    val groupId: UUID?,
    val assigneeId: UUID?,
    val firstComment: TicketComment,
) {
    init {
        require(subject.isNotBlank()) { "Ticket subject must not be blank" }
        require(firstComment.ticketId == id) { "The first comment must belong to the ticket" }
        when (kind) {
            TicketKind.CUSTOMER_REQUEST -> {
                require(firstComment.visibility == CommentVisibility.PUBLIC) {
                    "A customer web request must start with a public comment"
                }
                require(firstComment.authorType == CommentAuthorType.CUSTOMER) {
                    "A customer web request must start with a customer comment"
                }
            }
            TicketKind.AGENT_CREATED -> require(firstComment.authorType == CommentAuthorType.AGENT) {
                "An agent-created ticket must start with an agent comment"
            }
            TicketKind.INTERNAL_CHILD -> require(firstComment.visibility == CommentVisibility.INTERNAL) {
                "An internal child ticket must start with an internal comment"
            }
        }
        require(assigneeId == null || groupId != null) { "An assignee requires a group" }
    }

    companion object {
        fun submitFromWeb(
            ticketNumber: Long,
            requesterId: UUID,
            subject: String,
            message: String,
            now: Instant,
        ): Ticket {
            require(ticketNumber > 0) { "Ticket number must be positive" }
            require(message.isNotBlank()) { "Request message must not be blank" }

            val ticketId = UUID.randomUUID()
            return Ticket(
                id = ticketId,
                ticketNumber = ticketNumber,
                requesterId = requesterId,
                kind = TicketKind.CUSTOMER_REQUEST,
                subject = subject.trim(),
                status = TicketStatus.NEW,
                priority = TicketPriority.NORMAL,
                channel = TicketChannel.WEB,
                createdAt = now,
                updatedAt = now,
                groupId = null,
                assigneeId = null,
                firstComment = TicketComment(
                    id = UUID.randomUUID(),
                    ticketId = ticketId,
                    authorType = CommentAuthorType.CUSTOMER,
                    authorId = requesterId,
                    visibility = CommentVisibility.PUBLIC,
                    body = message.trim(),
                    createdAt = now,
                ),
            )
        }

        fun createByAgent(
            ticketNumber: Long,
            requesterId: UUID,
            subject: String,
            firstCommentBody: String,
            firstCommentVisibility: CommentVisibility,
            priority: TicketPriority,
            groupId: UUID?,
            assigneeId: UUID?,
            actorId: UUID,
            now: Instant,
        ): Ticket {
            require(ticketNumber > 0) { "Ticket number must be positive" }
            require(firstCommentBody.isNotBlank()) { "First comment must not be blank" }

            val ticketId = UUID.randomUUID()
            return Ticket(
                id = ticketId,
                ticketNumber = ticketNumber,
                requesterId = requesterId,
                kind = TicketKind.AGENT_CREATED,
                subject = subject.trim(),
                status = TicketStatus.NEW,
                priority = priority,
                channel = TicketChannel.AGENT,
                createdAt = now,
                updatedAt = now,
                groupId = groupId,
                assigneeId = assigneeId,
                firstComment = TicketComment(
                    id = UUID.randomUUID(),
                    ticketId = ticketId,
                    authorType = CommentAuthorType.AGENT,
                    authorId = actorId,
                    visibility = firstCommentVisibility,
                    body = firstCommentBody.trim(),
                    createdAt = now,
                ),
            )
        }

        fun createInternalChild(
            ticketNumber: Long,
            requesterId: UUID,
            subject: String,
            firstCommentBody: String,
            priority: TicketPriority,
            groupId: UUID,
            assigneeId: UUID?,
            actorId: UUID,
            now: Instant,
        ): Ticket {
            require(ticketNumber > 0) { "Ticket number must be positive" }
            require(firstCommentBody.isNotBlank()) { "First comment must not be blank" }

            val ticketId = UUID.randomUUID()
            return Ticket(
                id = ticketId,
                ticketNumber = ticketNumber,
                requesterId = requesterId,
                kind = TicketKind.INTERNAL_CHILD,
                subject = subject.trim(),
                status = TicketStatus.NEW,
                priority = priority,
                channel = TicketChannel.AGENT,
                createdAt = now,
                updatedAt = now,
                groupId = groupId,
                assigneeId = assigneeId,
                firstComment = TicketComment(
                    id = UUID.randomUUID(),
                    ticketId = ticketId,
                    authorType = CommentAuthorType.AGENT,
                    authorId = actorId,
                    visibility = CommentVisibility.INTERNAL,
                    body = firstCommentBody.trim(),
                    createdAt = now,
                ),
            )
        }
    }
}

internal data class TicketComment(
    val id: UUID,
    val ticketId: UUID,
    val authorType: CommentAuthorType,
    val authorId: UUID?,
    val visibility: CommentVisibility,
    val body: String,
    val createdAt: Instant,
) {
    init {
        require(body.isNotBlank()) { "Comment body must not be blank" }
    }
}
