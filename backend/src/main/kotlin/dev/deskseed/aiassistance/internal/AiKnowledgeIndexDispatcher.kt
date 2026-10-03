package dev.deskseed.aiassistance.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class ClaimedAiKnowledgeEvent(
    val eventId: UUID,
    val payload: String,
    val leaseOwner: String,
)

@Service
internal class AiKnowledgeIndexOutboxStore(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: AiIntegrationProperties,
    private val clock: Clock,
) {
    @Transactional
    fun claim(workerId: String): List<ClaimedAiKnowledgeEvent> {
        properties.validate()
        val now = Instant.now(clock)
        val rows = jdbcTemplate.query(
            """
            select event_id, payload_json::text
            from ai_knowledge_index_outbox
            where (status = 'PENDING' and available_at <= ?)
               or (status = 'LEASED' and lease_expires_at <= ?)
            order by available_at, created_at, event_id
            for update skip locked
            limit ?
            """.trimIndent(),
            { result, _ ->
                ClaimedAiKnowledgeEvent(
                    eventId = result.getObject("event_id", UUID::class.java),
                    payload = result.getString("payload_json"),
                    leaseOwner = workerId,
                )
            },
            Timestamp.from(now),
            Timestamp.from(now),
            properties.batchSize,
        )
        rows.forEach { row ->
            jdbcTemplate.update(
                """
                update ai_knowledge_index_outbox
                set status = 'LEASED', attempts = attempts + 1, lease_owner = ?, lease_expires_at = ?
                where event_id = ?
                """.trimIndent(),
                workerId,
                Timestamp.from(now.plusSeconds(properties.leaseSeconds)),
                row.eventId,
            )
        }
        return rows
    }

    @Transactional
    fun markDelivered(event: ClaimedAiKnowledgeEvent) {
        check(
            jdbcTemplate.update(
                """
                update ai_knowledge_index_outbox
                set status = 'DELIVERED', delivered_at = ?, lease_owner = null, lease_expires_at = null,
                    last_error_code = null
                where event_id = ? and status = 'LEASED' and lease_owner = ?
                """.trimIndent(),
                Timestamp.from(Instant.now(clock)),
                event.eventId,
                event.leaseOwner,
            ) == 1,
        ) { "AI knowledge outbox lease was lost" }
    }

    @Transactional
    fun markFailed(event: ClaimedAiKnowledgeEvent, code: String, retryable: Boolean) {
        jdbcTemplate.update(
            """
            update ai_knowledge_index_outbox
            set status = case when ? and attempts < ? then 'PENDING' else 'DEAD' end,
                available_at = ?, lease_owner = null, lease_expires_at = null, last_error_code = ?
            where event_id = ? and status = 'LEASED' and lease_owner = ?
            """.trimIndent(),
            retryable,
            properties.maxAttempts,
            Timestamp.from(Instant.now(clock).plusSeconds(2)),
            code.take(80),
            event.eventId,
            event.leaseOwner,
        )
    }
}

@Component
@ConditionalOnProperty(prefix = "deskseed.ai.integration", name = ["enabled"], havingValue = "true")
internal class AiKnowledgeIndexTransport(private val properties: AiIntegrationProperties) {
    private val client by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    fun send(event: ClaimedAiKnowledgeEvent): Int {
        properties.validate()
        val request = HttpRequest.newBuilder(
            URI.create(properties.baseUrl.trimEnd('/') + "/internal/v1/index-events"),
        )
            .timeout(Duration.ofMillis(properties.readTimeoutMillis))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .header("X-Deskseed-AI-Key-Id", properties.keyId)
            .header("Authorization", "Bearer ${properties.secret}")
            .POST(HttpRequest.BodyPublishers.ofString(event.payload))
            .build()
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}

@Component
@ConditionalOnProperty(prefix = "deskseed.ai.integration", name = ["enabled"], havingValue = "true")
internal class AiKnowledgeIndexDispatcher(
    private val store: AiKnowledgeIndexOutboxStore,
    private val transport: AiKnowledgeIndexTransport,
    private val properties: AiIntegrationProperties,
) {
    private val workerId = "backend-ai-kb-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${deskseed.ai.integration.index-delay-ms:1000}")
    fun scheduledDispatch() {
        if (properties.schedulingEnabled) dispatchOnce()
    }

    fun dispatchOnce(): Int {
        var delivered = 0
        store.claim(workerId).forEach { event ->
            try {
                when (val response = transport.send(event)) {
                    200, 202 -> {
                        store.markDelivered(event)
                        delivered += 1
                    }
                    409 -> store.markFailed(event, "REMOTE_CONFLICT", false)
                    429, in 500..599 -> store.markFailed(event, "REMOTE_RETRY_$response", true)
                    else -> store.markFailed(event, "REMOTE_REJECTED_$response", false)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                store.markFailed(event, "DELIVERY_INTERRUPTED", true)
            } catch (_: RuntimeException) {
                store.markFailed(event, "DELIVERY_FAILED", true)
            }
        }
        return delivered
    }
}
