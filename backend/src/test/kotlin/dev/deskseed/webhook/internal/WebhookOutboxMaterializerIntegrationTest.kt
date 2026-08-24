package dev.deskseed.webhook.internal

import dev.deskseed.eventpublication.DomainEventAppend
import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventPublicationPort
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.sql.Timestamp
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@dev.deskseed.testsupport.category.SlowTest
class WebhookOutboxMaterializerIntegrationTest {
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var eventPublication: EventPublicationPort
    @Autowired private lateinit var worker: WebhookEventOutboxWorker
    @Autowired private lateinit var transactions: TransactionTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute("truncate table webhook_delivery_attempts, webhook_deliveries, webhook_subscriptions, webhook_endpoint_secrets, webhook_endpoints, domain_event_outbox cascade")
        jdbcTemplate.update(
            """insert into staff_accounts (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at, version)
               values (?, 'webhook-materializer@example.com', 'webhook-materializer@example.com', 'Webhook materializer', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)
               on conflict (id) do nothing""",
            STAFF_ID,
            BCryptPasswordEncoder(4).encode("Webhook materializer test password 42"),
            Timestamp.from(Instant.parse("2026-08-18T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-18T00:00:00Z")),
        )
    }

    @Test
    fun `public Foundation event fans out once to every matching enabled endpoint and marks source delivered`() {
        val first = insertEndpoint("orders-a")
        val second = insertEndpoint("orders-b")
        val eventId = publish("ticket.created", DomainEventVisibility.PUBLIC)

        assertThat(worker.runOnce("materializer-a")).isTrue()
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from webhook_deliveries where event_id = ?", Long::class.java, eventId),
        ).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject("select status from domain_event_outbox where id = ?", String::class.java, eventId),
        ).isEqualTo("DELIVERED")
        assertThat(
            jdbcTemplate.queryForList("select endpoint_id from webhook_deliveries where event_id = ?", UUID::class.java, eventId),
        ).containsExactlyInAnyOrder(first, second)
        val checksumAndBody = jdbcTemplate.queryForMap(
            "select payload_checksum, payload_json::text as payload_body from webhook_deliveries where event_id = ? limit 1",
            eventId,
        )
        assertThat(checksumAndBody.getValue("payload_checksum")).isEqualTo(checksum(checksumAndBody.getValue("payload_body") as String))
    }

    @Test
    fun `internal Foundation event creates no externally visible delivery`() {
        insertEndpoint("internal-excluded")
        val eventId = publish("ticket.comment.created", DomainEventVisibility.INTERNAL)

        assertThat(worker.runOnce("materializer-internal")).isTrue()
        assertThat(jdbcTemplate.queryForObject("select count(*) from webhook_deliveries where event_id = ?", Long::class.java, eventId)).isZero()
        assertThat(jdbcTemplate.queryForObject("select status from domain_event_outbox where id = ?", String::class.java, eventId)).isEqualTo("DELIVERED")
    }

    private fun insertEndpoint(name: String): UUID {
        val endpointId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """insert into webhook_endpoints (
                 id, name, url, enabled, target_class, allowed_hostnames_json, allowed_ports_json, allowed_cidrs_json,
                 health_state, cooldown_until, consecutive_failures, last_succeeded_at, last_failed_at, created_by_staff_id,
                 created_at, updated_at, deactivated_at, version
               ) values (?, ?, 'https://203.0.113.10/hook', true, 'PUBLIC', '[]', '[443]', '[]', 'CLOSED', null, 0, null, null,
                         ?, ?, ?, null, 0)""",
            endpointId, name, STAFF_ID, now, now,
        )
        jdbcTemplate.update(
            "insert into webhook_subscriptions (endpoint_id, event_type, event_version, payload_policy, created_at) values (?, 'ticket.created', 1, 'METADATA_ONLY', ?)",
            endpointId, now,
        )
        jdbcTemplate.update(
            "insert into webhook_subscriptions (endpoint_id, event_type, event_version, payload_policy, created_at) values (?, 'ticket.comment.created', 1, 'METADATA_ONLY', ?)",
            endpointId, now,
        )
        return endpointId
    }

    private fun publish(type: String, visibility: DomainEventVisibility): UUID {
        val eventId = UUID.randomUUID()
        transactions.executeWithoutResult {
            eventPublication.append(
                DomainEventAppend(
                    DomainEventEnvelope(
                        id = eventId,
                        type = type,
                        version = 1,
                        occurredAt = Instant.now(),
                        subject = "ticket:${UUID.randomUUID()}",
                        sequence = null,
                        correlationId = "corr-webhook-test",
                        actorType = ActorType.SYSTEM,
                        actorId = null,
                        source = RequestSource.SYSTEM_JOB,
                        requestId = "request-webhook-test",
                        commandId = "command-webhook-test",
                        data = mapOf("ticketNumber" to "42"),
                    ),
                    visibility,
                ),
            )
        }
        return eventId
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val STAFF_ID: UUID = UUID.fromString("240ed447-289a-4e4d-a8b0-25528b1d15d1")
    }
}
