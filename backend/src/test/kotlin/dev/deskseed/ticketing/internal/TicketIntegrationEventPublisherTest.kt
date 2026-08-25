package dev.deskseed.ticketing.internal

import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventPublicationPort
import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class TicketIntegrationEventPublisherTest {
    private val recording = RecordingEventPublication()
    private val subject = TicketIntegrationEventPublisher(recording)
    private val ticketId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3350")
    private val actor = ActorRef(ActorType.STAFF, ticketId)
    private val context = CommandContext(
        source = RequestSource.AGENT_UI,
        requestId = "request-1042",
        correlationId = "correlation-1042",
        commandId = "command-1042",
    )
    private val now = Instant.parse("2026-08-18T00:00:00Z")

    @Test
    fun `ticket changes use metadata-only payloads and preserve public internal visibility`() {
        subject.ticketCreated(
            ticketId = ticketId,
            ticketNumber = 1042,
            kind = TicketKind.CUSTOMER_REQUEST,
            priority = TicketPriority.NORMAL,
            channel = TicketChannel.WEB,
            status = TicketStatus.NEW,
            firstCommentId = UUID.randomUUID(),
            firstCommentVisibility = CommentVisibility.PUBLIC,
            actor = actor,
            context = context,
            occurredAt = now,
        )
        subject.ticketUpdated(
            ticketId = ticketId,
            ticketNumber = 1042,
            kind = TicketKind.CUSTOMER_REQUEST,
            changedFields = setOf("status", "comments"),
            actor = actor,
            context = context,
            occurredAt = now,
        )
        subject.statusChanged(
            ticketId = ticketId,
            ticketNumber = 1042,
            kind = TicketKind.CUSTOMER_REQUEST,
            previousStatus = TicketStatus.NEW,
            currentStatus = TicketStatus.OPEN,
            actor = actor,
            context = context,
            occurredAt = now,
        )

        assertThat(recording.events.map { it.envelope.type }).containsExactly(
            "ticket.created",
            "ticket.comment.created",
            "ticket.updated",
            "ticket.status.changed",
        )
        assertThat(recording.events.map { it.visibility }).containsOnly(DomainEventVisibility.PUBLIC)
        recording.events.forEach { event ->
            assertThat(event.envelope.data.keys).doesNotContain("body", "secret", "token", "authorization")
            assertThat(event.envelope.actorType).isEqualTo(ActorType.STAFF)
            assertThat(event.envelope.source).isEqualTo(RequestSource.AGENT_UI)
        }
    }

    @Test
    fun `internal ticket facts never become public by default`() {
        listOf(TicketKind.INTERNAL_CHILD, TicketKind.INTERNAL_WORK_ITEM).forEach { kind ->
            recording.events.clear()
            subject.ticketCreated(
                ticketId = ticketId,
                ticketNumber = 1042,
                kind = kind,
                priority = TicketPriority.NORMAL,
                channel = TicketChannel.WEB,
                status = TicketStatus.NEW,
                firstCommentId = UUID.randomUUID(),
                firstCommentVisibility = CommentVisibility.INTERNAL,
                actor = actor,
                context = context,
                occurredAt = now,
            )
            subject.ticketUpdated(
                ticketId = ticketId,
                ticketNumber = 1042,
                kind = kind,
                changedFields = setOf("priority"),
                actor = actor,
                context = context,
                occurredAt = now,
            )
            subject.statusChanged(
                ticketId = ticketId,
                ticketNumber = 1042,
                kind = kind,
                previousStatus = TicketStatus.NEW,
                currentStatus = TicketStatus.OPEN,
                actor = actor,
                context = context,
                occurredAt = now,
            )

            assertThat(recording.events).allSatisfy { event ->
                assertThat(event.visibility).isEqualTo(DomainEventVisibility.INTERNAL)
            }
        }
    }

    private class RecordingEventPublication : EventPublicationPort {
        val events = mutableListOf<DomainEventAppend>()

        override fun append(event: DomainEventAppend): UUID {
            events += event
            return event.envelope.id
        }
    }
}
