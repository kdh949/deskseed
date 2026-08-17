package dev.deskseed.webhook

import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import java.time.Instant
import java.util.UUID

data class SerializedWebhookEvent(
    val eventId: UUID,
    val eventType: String,
    val eventVersion: Int,
    val occurredAt: Instant,
    val bodyJson: String,
)

/** Provider seam for versioned public webhook representations; never expose the raw outbox envelope directly. */
fun interface WebhookEventSerializer {
    fun serialize(
        event: DomainEventEnvelope,
        visibility: DomainEventVisibility,
        policy: WebhookPayloadPolicy,
    ): SerializedWebhookEvent?
}

interface WebhookEventMaterializer {
    /** Creates delivery intents for currently enabled matching endpoint subscriptions. */
    fun materialize(event: DomainEventEnvelope, visibility: DomainEventVisibility): Int
}
