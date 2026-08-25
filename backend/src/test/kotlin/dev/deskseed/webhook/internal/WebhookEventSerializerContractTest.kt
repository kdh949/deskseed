package dev.deskseed.webhook.internal

import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.webhook.WebhookPayloadPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.ContractTest
class WebhookEventSerializerContractTest {
    private val serializer = TicketWebhookEventSerializer(ObjectMapper())

    @Test
    fun `each administratively subscribable event and payload policy has exactly one serializer`() {
        ADMINISTRATIVELY_SUBSCRIBABLE_EVENT_TYPES.forEach { eventType ->
            WebhookPayloadPolicy.entries.forEach { policy ->
                val serialized = listOf(serializer).mapNotNull {
                    it.serialize(event(eventType), DomainEventVisibility.PUBLIC, policy)
                }

                assertThat(serialized)
                    .withFailMessage("$eventType v1 with $policy must have exactly one serializer")
                    .hasSize(1)
            }
        }
    }

    private fun event(type: String) = DomainEventEnvelope(
        id = UUID.randomUUID(),
        type = type,
        version = 1,
        occurredAt = Instant.parse("2026-08-18T00:00:00Z"),
        subject = "ticket:018f7c2c-7348-7a32-a971-4c9a845b3350",
        sequence = 0,
        correlationId = "correlation-1042",
        causationId = "command-1042",
        actorType = ActorType.STAFF,
        actorId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3350"),
        source = RequestSource.AGENT_UI,
        requestId = "request-1042",
        commandId = "command-1042",
        data = mapOf("ticketNumber" to "1042"),
    )

    private companion object {
        val ADMINISTRATIVELY_SUBSCRIBABLE_EVENT_TYPES = setOf(
            "ticket.created",
            "ticket.updated",
            "ticket.comment.created",
            "ticket.status.changed",
            "ticket.sla.changed",
            "attachment.ready",
            "ticket.trigger.executed",
        )
    }
}
