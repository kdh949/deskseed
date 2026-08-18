package dev.deskseed.webhook.internal

import dev.deskseed.eventpublication.DomainEventEnvelope
import dev.deskseed.eventpublication.DomainEventVisibility
import dev.deskseed.eventpublication.EventOutboxOperations
import dev.deskseed.webhook.SerializedWebhookEvent
import dev.deskseed.webhook.WebhookEventMaterializer
import dev.deskseed.webhook.WebhookEventSerializer
import dev.deskseed.webhook.WebhookEventCatalog
import dev.deskseed.webhook.WebhookPayloadPolicy
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Component
internal class TicketWebhookEventSerializer(private val objectMapper: ObjectMapper) : WebhookEventSerializer {
    override fun serialize(
        event: DomainEventEnvelope,
        visibility: DomainEventVisibility,
        policy: WebhookPayloadPolicy,
    ): SerializedWebhookEvent? {
        if (visibility != DomainEventVisibility.PUBLIC || !WebhookEventCatalog.supports(event.type, event.version, policy)) return null
        // Foundation events deliberately have no comment body. PUBLIC_CONTENT is reserved for a later
        // provider that can prove its field allowlist; this v1 serializer remains metadata-only.
        val payload = linkedMapOf(
            "id" to event.id.toString(),
            "type" to event.type,
            "version" to event.version,
            "occurredAt" to event.occurredAt.toString(),
            "subject" to event.subject,
            "data" to event.data.toSortedMap(),
        )
        return SerializedWebhookEvent(event.id, event.type, event.version, event.occurredAt, objectMapper.writeValueAsString(payload))
    }
}

@Service
internal class JdbcWebhookEventMaterializer(
    private val jdbc: JdbcTemplate,
    private val serializers: List<WebhookEventSerializer>,
    private val clock: Clock,
) : WebhookEventMaterializer {
    @Transactional
    override fun materialize(event: DomainEventEnvelope, visibility: DomainEventVisibility): Int {
        if (visibility != DomainEventVisibility.PUBLIC) return 0
        val subscriptions = jdbc.query(
            """
            select endpoint_id, payload_policy, endpoint.version as endpoint_version
              from webhook_subscriptions subscription
              join webhook_endpoints endpoint on endpoint.id = subscription.endpoint_id
             where endpoint.enabled = true and endpoint.deactivated_at is null
               and subscription.event_type = ? and subscription.event_version = ?
            """.trimIndent(),
            { row, _ ->
                Subscription(
                    row.getObject("endpoint_id", UUID::class.java),
                    WebhookPayloadPolicy.valueOf(row.getString("payload_policy")),
                    row.getLong("endpoint_version"),
                )
            },
            event.type, event.version,
        )
        var created = 0
        subscriptions.forEach { subscription ->
            val serialized = serializers.mapNotNull { it.serialize(event, visibility, subscription.payloadPolicy) }
                .singleOrNull() ?: throw IllegalStateException("Webhook event serializer is unavailable or ambiguous")
            val now = Instant.now(clock)
            val deliveryId = UUID.randomUUID()
            val storedPayload = jdbc.query(
                """
                insert into webhook_deliveries (
                    id, endpoint_id, endpoint_version, event_id, event_type, event_version, payload_checksum, payload_json, status,
                    attempt_count, next_attempt_at, lease_owner, lease_expires_at, error_category, created_at, updated_at, completed_at, version
                ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING', 0, ?, null, null, null, ?, ?, null, 0)
                on conflict (endpoint_id, event_id) do nothing
                returning payload_json::text
                """.trimIndent(),
                { row, _ -> row.getString(1) },
                deliveryId, subscription.endpointId, subscription.endpointVersion, serialized.eventId, serialized.eventType, serialized.eventVersion,
                "0".repeat(64), serialized.bodyJson, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
            ).singleOrNull()
            if (storedPayload != null) {
                jdbc.update("update webhook_deliveries set payload_checksum = ? where id = ?", sha256(storedPayload), deliveryId)
                created += 1
            }
        }
        return created
    }

    private data class Subscription(val endpointId: UUID, val payloadPolicy: WebhookPayloadPolicy, val endpointVersion: Long)
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Converts a committed Foundation event into durable webhook fan-out; no network I/O occurs here. */
@Component
internal class WebhookEventOutboxWorker(
    private val outbox: EventOutboxOperations,
    private val materializer: WebhookEventMaterializer,
) {
    fun runOnce(workerId: String = "webhook-materializer"): Boolean {
        val claimed = outbox.claimNext(workerId, 60) ?: return false
        materializer.materialize(claimed.envelope, claimed.visibility)
        outbox.markDelivered(claimed.envelope.id, workerId)
        return true
    }

    @Scheduled(fixedDelayString = "\${deskseed.webhook.materializer-delay-ms:1000}")
    fun materializeDueEvents() {
        repeat(100) { if (!runOnce()) return }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
internal class WebhookSchedulingConfiguration
