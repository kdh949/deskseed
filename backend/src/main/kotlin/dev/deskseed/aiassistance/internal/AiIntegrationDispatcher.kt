package dev.deskseed.aiassistance.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
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

@ConfigurationProperties("deskseed.ai.integration")
internal data class AiIntegrationProperties(
    var enabled: Boolean = false,
    var schedulingEnabled: Boolean = false,
    var baseUrl: String = "http://ai-service:8090",
    var keyId: String = "",
    var secret: String = "",
    var batchSize: Int = 20,
    var leaseSeconds: Long = 30,
    var maxAttempts: Int = 8,
    var connectTimeoutMillis: Long = 2_000,
    var readTimeoutMillis: Long = 10_000,
) {
    fun validate() {
        val uri = URI.create(baseUrl)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null)
        require(uri.path.isNullOrEmpty() || uri.path == "/")
        require(uri.query == null && uri.fragment == null)
        require(keyId.length in 1..80 && keyId.none(Char::isISOControl))
        require(secret.length in 16..512 && secret.none(Char::isISOControl))
        require(batchSize in 1..100)
        require(leaseSeconds in 5..300)
        require(maxAttempts in 1..20)
        require(connectTimeoutMillis in 100..30_000)
        require(readTimeoutMillis in 100..120_000)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiIntegrationProperties::class)
internal class AiIntegrationConfiguration

internal data class ClaimedAiIntegrationEvent(
    val eventId: UUID,
    val jobId: UUID,
    val eventType: String,
    val requestRevision: Long,
    val payload: String,
    val leaseOwner: String,
)

@Service
internal class AiIntegrationOutboxStore(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: AiIntegrationProperties,
    private val clock: Clock,
) {
    @Transactional
    fun claim(workerId: String): List<ClaimedAiIntegrationEvent> {
        properties.validate()
        require(workerId.matches(Regex("^[A-Za-z0-9._:-]{1,100}$")))
        val now = Instant.now(clock)
        val leaseExpiry = now.plusSeconds(properties.leaseSeconds)
        val rows = jdbcTemplate.query(
            """
            select event_id, job_id, event_type, request_revision, payload_json::text
            from ai_integration_outbox
            where (status = 'PENDING' and available_at <= ?)
               or (status = 'LEASED' and lease_expires_at <= ?)
            order by available_at, created_at, event_id
            for update skip locked
            limit ?
            """.trimIndent(),
            { result, _ ->
                ClaimedAiIntegrationEvent(
                    eventId = result.getObject("event_id", UUID::class.java),
                    jobId = result.getObject("job_id", UUID::class.java),
                    eventType = result.getString("event_type"),
                    requestRevision = result.getLong("request_revision"),
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
                update ai_integration_outbox
                set status = 'LEASED', attempts = attempts + 1, lease_owner = ?, lease_expires_at = ?
                where event_id = ?
                """.trimIndent(),
                workerId,
                Timestamp.from(leaseExpiry),
                row.eventId,
            )
        }
        return rows
    }

    @Transactional
    fun markDelivered(event: ClaimedAiIntegrationEvent) {
        val updated = jdbcTemplate.update(
            """
            update ai_integration_outbox
            set status = 'DELIVERED', delivered_at = ?, lease_owner = null, lease_expires_at = null,
                last_error_code = null
            where event_id = ? and status = 'LEASED' and lease_owner = ?
            """.trimIndent(),
            Timestamp.from(Instant.now(clock)),
            event.eventId,
            event.leaseOwner,
        )
        check(updated == 1) { "AI integration outbox lease was lost" }
    }

    @Transactional
    fun markFailed(event: ClaimedAiIntegrationEvent, errorCode: String, retryable: Boolean) {
        val now = Instant.now(clock)
        jdbcTemplate.update(
            """
            update ai_integration_outbox
            set status = case when ? and attempts < ? then 'PENDING' else 'DEAD' end,
                available_at = ?, lease_owner = null, lease_expires_at = null, last_error_code = ?
            where event_id = ? and status = 'LEASED' and lease_owner = ?
            """.trimIndent(),
            retryable,
            properties.maxAttempts,
            Timestamp.from(now.plusSeconds(2)),
            errorCode.filter { it.isUpperCase() || it.isDigit() || it == '_' }.take(80).ifBlank { "DELIVERY_FAILED" },
            event.eventId,
            event.leaseOwner,
        )
    }
}

internal data class AiIntegrationTransportResult(val status: Int)

internal fun interface AiIntegrationTransport {
    fun send(event: ClaimedAiIntegrationEvent): AiIntegrationTransportResult
}

@Component
@ConditionalOnProperty(prefix = "deskseed.ai.integration", name = ["enabled"], havingValue = "true")
internal class HttpAiIntegrationTransport(
    private val properties: AiIntegrationProperties,
) : AiIntegrationTransport {
    private val client: HttpClient by lazy {
        properties.validate()
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun send(event: ClaimedAiIntegrationEvent): AiIntegrationTransportResult {
        val path = when (event.eventType) {
            "JOB_REQUESTED" -> "/internal/v1/jobs"
            "JOB_CANCELLED" -> "/internal/v1/jobs/${event.jobId}/cancel"
            "JOB_FEEDBACK" -> "/internal/v1/feedback"
            else -> error("Unsupported AI integration event type")
        }
        val request = HttpRequest.newBuilder(URI.create(properties.baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofMillis(properties.readTimeoutMillis))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .header("X-Deskseed-AI-Key-Id", properties.keyId)
            .header("Authorization", "Bearer ${properties.secret}")
            .POST(HttpRequest.BodyPublishers.ofString(event.payload))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        return AiIntegrationTransportResult(response.statusCode())
    }
}

@Component
@ConditionalOnProperty(prefix = "deskseed.ai.integration", name = ["enabled"], havingValue = "true")
internal class AiIntegrationDispatcher(
    private val store: AiIntegrationOutboxStore,
    private val transport: AiIntegrationTransport,
    private val properties: AiIntegrationProperties,
) {
    private val workerId = "backend-ai-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${deskseed.ai.integration.dispatch-delay-ms:500}")
    fun scheduledDispatch() {
        if (!properties.schedulingEnabled) return
        dispatchOnce()
    }

    fun dispatchOnce(): Int {
        var delivered = 0
        store.claim(workerId).forEach { event ->
            try {
                val response = transport.send(event)
                when (response.status) {
                    200, 202 -> {
                        store.markDelivered(event)
                        delivered += 1
                    }
                    409 -> store.markFailed(event, "REMOTE_CONFLICT", retryable = false)
                    400, 401, 403, 404, 422 -> store.markFailed(event, "REMOTE_REJECTED_${response.status}", retryable = false)
                    429, in 500..599 -> store.markFailed(event, "REMOTE_RETRY_${response.status}", retryable = true)
                    else -> store.markFailed(event, "REMOTE_HTTP_${response.status}", retryable = false)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                store.markFailed(event, "DELIVERY_INTERRUPTED", retryable = true)
            } catch (_: RuntimeException) {
                store.markFailed(event, "DELIVERY_FAILED", retryable = true)
            }
        }
        return delivered
    }
}
