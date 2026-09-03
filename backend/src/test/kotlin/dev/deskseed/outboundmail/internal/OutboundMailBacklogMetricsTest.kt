package dev.deskseed.outboundmail.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@dev.deskseed.testsupport.category.FastTest
class OutboundMailBacklogMetricsTest {
    @Test
    fun `backlog metrics expose count and non-negative oldest age without message data`() {
        val now = Instant.parse("2026-09-04T00:00:00Z")
        val statuses = listOf(MailIntentStatus.QUEUED, MailIntentStatus.RETRY_WAIT, MailIntentStatus.SENDING)
        val repository = mock(OutboundMailIntentRepository::class.java)
        `when`(repository.countByStatusIn(statuses)).thenReturn(3)
        `when`(repository.findOldestQueuedAtByStatusIn(statuses)).thenReturn(now.minusSeconds(42))
        val registry = SimpleMeterRegistry()

        OutboundMailBacklogMetrics(repository, Clock.fixed(now, ZoneOffset.UTC), registry)

        assertThat(registry.get("deskseed.mail.outbox.backlog").gauge().value()).isEqualTo(3.0)
        assertThat(registry.get("deskseed.mail.outbox.oldest.age").gauge().value()).isEqualTo(42.0)
        assertThat(registry.meters.flatMap { it.id.tags }.map { it.key })
            .doesNotContain("recipient", "email", "intent_id", "ticket_id")
    }

    @Test
    fun `oldest age is zero for an empty or future-dated backlog`() {
        val now = Instant.parse("2026-09-04T00:00:00Z")
        val statuses = listOf(MailIntentStatus.QUEUED, MailIntentStatus.RETRY_WAIT, MailIntentStatus.SENDING)
        val repository = mock(OutboundMailIntentRepository::class.java)
        `when`(repository.countByStatusIn(statuses)).thenReturn(0)
        `when`(repository.findOldestQueuedAtByStatusIn(statuses)).thenReturn(now.plusSeconds(5))
        val registry = SimpleMeterRegistry()

        OutboundMailBacklogMetrics(repository, Clock.fixed(now, ZoneOffset.UTC), registry)

        assertThat(registry.get("deskseed.mail.outbox.oldest.age").gauge().value()).isZero()
    }
}
