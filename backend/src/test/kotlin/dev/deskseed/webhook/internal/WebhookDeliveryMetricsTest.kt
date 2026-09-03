package dev.deskseed.webhook.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.jdbc.core.JdbcTemplate

@dev.deskseed.testsupport.category.FastTest
class WebhookDeliveryMetricsTest {
    @Test
    fun `delivery metrics expose backlog count and oldest age without delivery identity`() {
        val jdbc = mock(JdbcTemplate::class.java)
        `when`(jdbc.queryForObject(WEBHOOK_BACKLOG_COUNT_SQL, Long::class.java)).thenReturn(4)
        `when`(jdbc.queryForObject(WEBHOOK_BACKLOG_OLDEST_AGE_SQL, Double::class.java)).thenReturn(17.5)
        val registry = SimpleMeterRegistry()

        WebhookDeliveryMetrics(jdbc, registry)

        assertThat(registry.get("deskseed.webhook.delivery.backlog").gauge().value()).isEqualTo(4.0)
        assertThat(registry.get("deskseed.webhook.delivery.backlog.oldest.age").gauge().value()).isEqualTo(17.5)
        assertThat(registry.meters.flatMap { it.id.tags }.map { it.key })
            .doesNotContain("endpoint_id", "delivery_id", "event_id", "url")
    }
}
