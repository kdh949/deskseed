package dev.deskseed.eventpublication.internal

import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class DomainEventEnvelopeTest {
    @Test
    fun `rejects control characters and oversized public-safe event payload values`() {
        assertThatThrownBy {
            envelope(data = mapOf("comment" to "unsafe\nbody"))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            envelope(data = mapOf("summary" to "x".repeat(501)))
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            envelope(data = mapOf("body" to "customer message"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects an unversioned or non-dotted event type`() {
        assertThatThrownBy { envelope(type = "ticket") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { envelope(version = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun envelope(
        type: String = "ticket.updated",
        version: Int = 1,
        data: Map<String, String> = mapOf("ticketNumber" to "1042"),
    ) = DomainEventEnvelope(
        id = UUID.randomUUID(),
        type = type,
        version = version,
        occurredAt = Instant.parse("2026-08-18T00:00:00Z"),
        subject = "ticket:018f7c2c-7348-7a32-a971-4c9a845b3350",
        sequence = null,
        correlationId = "correlation-1042",
        actorType = ActorType.STAFF,
        actorId = UUID.fromString("018f7c2c-7348-7a32-a971-4c9a845b3350"),
        source = RequestSource.AGENT_UI,
        requestId = "request-1042",
        commandId = "command-1042",
        data = data,
    )
}
