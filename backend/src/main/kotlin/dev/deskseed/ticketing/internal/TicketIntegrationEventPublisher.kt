package dev.deskseed.ticketing.internal

import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventPublicationPort
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContext
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Creates bounded integration facts from already-persisted ticket state.
 *
 * The ticket command owns its audit entries; this component owns a separate,
 * at-least-once delivery intent and never copies a comment body into it.
 */
@Component
internal class TicketIntegrationEventPublisher(
    private val eventPublication: EventPublicationPort,
) {
    fun ticketCreated(
        ticketId: UUID,
        ticketNumber: Long,
        kind: TicketKind,
        priority: TicketPriority,
        channel: TicketChannel,
        status: TicketStatus,
        firstCommentId: UUID,
        firstCommentVisibility: CommentVisibility,
        actor: ActorRef,
        context: CommandContext,
        occurredAt: Instant,
    ) {
        val visibility = ticketVisibility(kind)
        append(
            type = "ticket.created",
            ticketId = ticketId,
            actor = actor,
            context = context,
            occurredAt = occurredAt,
            visibility = visibility,
            data = mapOf(
                "ticketNumber" to ticketNumber.toString(),
                "kind" to kind.name,
                "priority" to priority.name,
                "channel" to channel.name,
                "status" to status.name,
            ),
        )
        commentCreated(
            ticketId = ticketId,
            ticketNumber = ticketNumber,
            commentId = firstCommentId,
            visibility = firstCommentVisibility,
            actor = actor,
            context = context,
            occurredAt = occurredAt,
        )
    }

    fun ticketUpdated(
        ticketId: UUID,
        ticketNumber: Long,
        kind: TicketKind,
        changedFields: Set<String>,
        actor: ActorRef,
        context: CommandContext,
        occurredAt: Instant,
    ) {
        append(
            type = "ticket.updated",
            ticketId = ticketId,
            actor = actor,
            context = context,
            occurredAt = occurredAt,
            visibility = ticketVisibility(kind),
            data = mapOf(
                "ticketNumber" to ticketNumber.toString(),
                "changedFields" to changedFields.sorted().joinToString(","),
            ),
        )
    }

    fun commentCreated(
        ticketId: UUID,
        ticketNumber: Long,
        commentId: UUID,
        visibility: CommentVisibility,
        actor: ActorRef,
        context: CommandContext,
        occurredAt: Instant,
    ) {
        append(
            type = "ticket.comment.created",
            ticketId = ticketId,
            actor = actor,
            context = context,
            occurredAt = occurredAt,
            visibility = DomainEventVisibility.valueOf(visibility.name),
            data = mapOf(
                "ticketNumber" to ticketNumber.toString(),
                "commentId" to commentId.toString(),
                "visibility" to visibility.name,
            ),
        )
    }

    fun statusChanged(
        ticketId: UUID,
        ticketNumber: Long,
        kind: TicketKind,
        previousStatus: TicketStatus,
        currentStatus: TicketStatus,
        actor: ActorRef,
        context: CommandContext,
        occurredAt: Instant,
    ) {
        if (previousStatus == currentStatus) return
        append(
            type = "ticket.status.changed",
            ticketId = ticketId,
            actor = actor,
            context = context,
            occurredAt = occurredAt,
            visibility = ticketVisibility(kind),
            data = mapOf(
                "ticketNumber" to ticketNumber.toString(),
                "previousStatus" to previousStatus.name,
                "currentStatus" to currentStatus.name,
            ),
        )
    }

    private fun append(
        type: String,
        ticketId: UUID,
        actor: ActorRef,
        context: CommandContext,
        occurredAt: Instant,
        visibility: DomainEventVisibility,
        data: Map<String, String>,
    ) {
        eventPublication.append(
            DomainEventAppend(
                envelope = DomainEventEnvelope(
                    id = UUID.randomUUID(),
                    type = type,
                    version = 1,
                    occurredAt = occurredAt,
                    subject = "ticket:$ticketId",
                    sequence = null,
                    correlationId = context.correlationId,
                    causationId = context.commandId,
                    actorType = actor.actorType,
                    actorId = actor.actorId,
                    source = context.source,
                    requestId = context.requestId,
                    commandId = context.commandId,
                    data = data,
                ),
                visibility = visibility,
            ),
        )
    }

    private fun ticketVisibility(kind: TicketKind): DomainEventVisibility =
        if (kind == TicketKind.INTERNAL_CHILD) DomainEventVisibility.INTERNAL else DomainEventVisibility.PUBLIC
}
