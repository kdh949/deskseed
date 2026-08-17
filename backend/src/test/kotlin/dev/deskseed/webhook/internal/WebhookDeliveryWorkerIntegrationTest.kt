package dev.deskseed.webhook.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.webhook.delivery.enabled=false",
        "deskseed.webhook.delivery.scheduling-enabled=false",
        "deskseed.webhook.delivery.max-attempts=2",
        "deskseed.webhook.delivery.circuit-failure-threshold=3",
    ],
)
@Import(WebhookDeliveryWorkerIntegrationTest.FakeTransportConfiguration::class)
@Testcontainers
class WebhookDeliveryWorkerIntegrationTest {
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var worker: WebhookDeliveryWorker
    @Autowired private lateinit var secretCipher: WebhookSecretCipher
    @Autowired private lateinit var transport: RecordingTransport

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute("truncate table webhook_delivery_attempts, webhook_deliveries, webhook_subscriptions, webhook_endpoint_secrets, webhook_endpoints, domain_event_outbox cascade")
        jdbcTemplate.update(
            """insert into staff_accounts (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at, version)
               values (?, 'webhook-worker@example.com', 'webhook-worker@example.com', 'Webhook worker', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)
               on conflict (id) do nothing""",
            STAFF_ID,
            BCryptPasswordEncoder(4).encode("Webhook worker test password 42"),
            Timestamp.from(FIXED_NOW),
            Timestamp.from(FIXED_NOW),
        )
        transport.reset()
    }

    @Test
    fun `successful delivery is signed after durable claim and records only safe attempt metadata`() {
        val endpointId = insertEndpoint()
        val deliveryId = insertDelivery(endpointId)
        transport.responses.add(Result.success(WebhookTransportResult(204)))

        assertThat(worker.runDueBatch("worker-success")).isEqualTo(1)

        assertThat(jdbcTemplate.queryForObject("select status from webhook_deliveries where id = ?", String::class.java, deliveryId))
            .isEqualTo("SUCCEEDED")
        assertThat(jdbcTemplate.queryForObject("select active_delivery_count from webhook_endpoints where id = ?", Int::class.java, endpointId))
            .isZero()
        assertThat(jdbcTemplate.queryForObject("select response_status from webhook_delivery_attempts where delivery_id = ?", Int::class.java, deliveryId))
            .isEqualTo(204)
        assertThat(transport.claims.single().rawBody.toString(Charsets.UTF_8)).isEqualTo("{\"ticketNumber\": 42}")
        assertThat(transport.claims.single().secret).isEqualTo("worker-webhook-secret")
    }

    @Test
    fun `retryable failure schedules retry then dead letters at the configured maximum`() {
        val endpointId = insertEndpoint()
        val deliveryId = insertDelivery(endpointId)
        transport.responses.add(Result.success(WebhookTransportResult(503)))

        assertThat(worker.runDueBatch("worker-retry-a")).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("select status from webhook_deliveries where id = ?", String::class.java, deliveryId))
            .isEqualTo("RETRY_SCHEDULED")
        jdbcTemplate.update("update webhook_deliveries set next_attempt_at = now() where id = ?", deliveryId)
        transport.responses.add(Result.success(WebhookTransportResult(503)))

        assertThat(worker.runDueBatch("worker-retry-b")).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("select status from webhook_deliveries where id = ?", String::class.java, deliveryId))
            .isEqualTo("DEAD_LETTERED")
        assertThat(jdbcTemplate.queryForObject("select error_category from webhook_deliveries where id = ?", String::class.java, deliveryId))
            .isEqualTo("HTTP_503")
    }

    @Test
    fun `threshold failure opens only the affected endpoint circuit and preserves its retry intent`() {
        val endpointId = insertEndpoint()
        jdbcTemplate.update("update webhook_endpoints set consecutive_failures = 2 where id = ?", endpointId)
        val deliveryId = insertDelivery(endpointId)
        transport.responses.add(Result.success(WebhookTransportResult(503)))

        assertThat(worker.runDueBatch("worker-circuit")).isEqualTo(1)

        assertThat(jdbcTemplate.queryForObject("select health_state from webhook_endpoints where id = ?", String::class.java, endpointId))
            .isEqualTo("OPEN")
        assertThat(jdbcTemplate.queryForObject("select status from webhook_deliveries where id = ?", String::class.java, deliveryId))
            .isEqualTo("RETRY_SCHEDULED")
        assertThat(jdbcTemplate.queryForObject("select cooldown_until is not null from webhook_endpoints where id = ?", Boolean::class.java, endpointId))
            .isTrue()
    }

    @Test
    fun `expired in-flight lease returns the delivery without mutating ticket state`() {
        val endpointId = insertEndpoint()
        val deliveryId = insertDelivery(endpointId, status = "IN_FLIGHT", attemptCount = 1, leaseOwner = "abandoned", expiredLease = true)
        jdbcTemplate.update("update webhook_endpoints set active_delivery_count = 1 where id = ?", endpointId)
        jdbcTemplate.update(
            """insert into webhook_delivery_attempts (id, delivery_id, attempt_number, request_timestamp, response_headers_json)
               values (?, ?, 1, ?, '{}')""",
            UUID.randomUUID(), deliveryId, Timestamp.from(FIXED_NOW),
        )

        assertThat(worker.recoverExpiredLeases()).isEqualTo(1)

        assertThat(jdbcTemplate.queryForObject("select status from webhook_deliveries where id = ?", String::class.java, deliveryId))
            .isEqualTo("RETRY_SCHEDULED")
        assertThat(jdbcTemplate.queryForObject("select active_delivery_count from webhook_endpoints where id = ?", Int::class.java, endpointId))
            .isZero()
        assertThat(jdbcTemplate.queryForObject("select error_category from webhook_delivery_attempts where delivery_id = ?", String::class.java, deliveryId))
            .isEqualTo("WORKER_LEASE_EXPIRED")
    }

    private fun insertEndpoint(): UUID {
        val endpointId = UUID.randomUUID()
        jdbcTemplate.update(
            """insert into webhook_endpoints (
                id, name, url, enabled, target_class, allowed_hostnames_json, allowed_ports_json, allowed_cidrs_json,
                health_state, cooldown_until, consecutive_failures, last_succeeded_at, last_failed_at,
                created_by_staff_id, created_at, updated_at, deactivated_at, version
            ) values (?, ?, 'https://example.com/webhook', true, 'PUBLIC', '[]', '[443]', '[]', 'CLOSED', null, 0, null, null, ?, ?, ?, null, 0)""",
            endpointId, "endpoint-$endpointId", STAFF_ID, Timestamp.from(FIXED_NOW), Timestamp.from(FIXED_NOW),
        )
        val secretId = UUID.randomUUID()
        val encrypted = secretCipher.encrypt("worker-webhook-secret", secretId)
        jdbcTemplate.update(
            """insert into webhook_endpoint_secrets (
                id, endpoint_id, sequence, ciphertext, nonce, key_version, status, overlap_expires_at,
                created_by_staff_id, created_at, revoked_at
            ) values (?, ?, 1, ?, ?, ?, 'ACTIVE', null, ?, ?, null)""",
            secretId, endpointId, encrypted.ciphertext, encrypted.nonce, encrypted.keyVersion, STAFF_ID, Timestamp.from(FIXED_NOW),
        )
        return endpointId
    }

    private fun insertDelivery(
        endpointId: UUID,
        status: String = "PENDING",
        attemptCount: Int = 0,
        leaseOwner: String? = null,
        expiredLease: Boolean = false,
    ): UUID {
        val deliveryId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        val payload = "{\"ticketNumber\":42}"
        val now = Instant.now().minusSeconds(5)
        jdbcTemplate.update(
            """insert into webhook_deliveries (
                id, endpoint_id, endpoint_version, event_id, event_type, event_version, payload_checksum, payload_json, status,
                attempt_count, next_attempt_at, lease_owner, lease_expires_at, error_category, created_at, updated_at, completed_at, version
            ) values (?, ?, 0, ?, 'ticket.created', 1, ?, cast(? as jsonb), ?, ?, ?, ?, ?, null, ?, ?, null, 0)""",
            deliveryId, endpointId, eventId, checksum(payload), payload, status, attemptCount,
            Timestamp.from(now), leaseOwner, if (expiredLease) Timestamp.from(now.minusSeconds(1)) else null,
            Timestamp.from(now), Timestamp.from(now),
        )
        return deliveryId
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @TestConfiguration(proxyBeanMethods = false)
    internal class FakeTransportConfiguration {
        @Bean @Primary fun fakeWebhookDeliveryTransport() = RecordingTransport()
    }

    internal class RecordingTransport : WebhookDeliveryTransport {
        val claims = ConcurrentLinkedQueue<ClaimedWebhookDelivery>()
        val responses = ConcurrentLinkedQueue<Result<WebhookTransportResult>>()

        override fun send(claim: ClaimedWebhookDelivery): WebhookTransportResult {
            claims.add(claim)
            return responses.poll()?.getOrThrow() ?: WebhookTransportResult(204)
        }

        fun reset() {
            claims.clear()
            responses.clear()
        }
    }

    companion object {
        val STAFF_ID: UUID = UUID.fromString("b6d34baf-2063-48e8-86ee-d2d6aec08942")
        val FIXED_NOW: Instant = Instant.parse("2026-08-18T00:00:00Z")

        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
