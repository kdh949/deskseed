package dev.deskseed.webhook.internal

import dev.deskseed.webhook.WebhookHealthState
import dev.deskseed.webhook.WebhookTargetClass
import dev.deskseed.webhook.WebhookTargetPolicy
import dev.deskseed.webhook.WebhookTargetRejectedException
import dev.deskseed.webhook.WebhookTargetValidator
import dev.deskseed.webhook.WebhookSignatureSigner
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import org.springframework.web.client.ResourceAccessException
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

@ConfigurationProperties("deskseed.webhook.delivery")
internal data class WebhookDeliveryProperties(
    var enabled: Boolean = false,
    var schedulingEnabled: Boolean = false,
    var batchSize: Int = 50,
    var globalConcurrency: Int = 8,
    var perEndpointConcurrency: Int = 2,
    var leaseSeconds: Long = 60,
    var maxAttempts: Int = 10,
    var circuitFailureThreshold: Int = 3,
    var circuitCooldownSeconds: Long = 60,
    var connectTimeoutMillis: Long = 5_000,
    var readTimeoutMillis: Long = 10_000,
    var retryBaseSeconds: Long = 2,
    var retryMaxSeconds: Long = 300,
) {
    fun validate() {
        require(batchSize in 1..1_000)
        require(globalConcurrency in 1..100)
        require(perEndpointConcurrency in 1..20)
        require(leaseSeconds in 5..300)
        require(maxAttempts in 1..20)
        require(circuitFailureThreshold in 1..20)
        require(circuitCooldownSeconds in 1..3_600)
        require(connectTimeoutMillis in 100..60_000)
        require(readTimeoutMillis in 100..120_000)
        require(retryBaseSeconds in 1..300)
        require(retryMaxSeconds in retryBaseSeconds..3_600)
    }
}

internal data class ClaimedWebhookDelivery(
    val deliveryId: UUID,
    val endpointId: UUID,
    val eventId: UUID,
    val eventType: String,
    val rawBody: ByteArray,
    val attemptNumber: Int,
    val claimedAt: Instant,
    val leaseOwner: String,
    val endpointUrl: String,
    val targetPolicy: WebhookTargetPolicy,
    val secret: String,
)

internal data class WebhookTransportResult(
    val status: Int,
    val retryAfter: Duration? = null,
)

internal class WebhookTransportException(
    val retryable: Boolean,
    val category: String,
    cause: Throwable? = null,
) : RuntimeException(category, cause)

internal fun interface WebhookDeliveryTransport {
    /** Executes outside every ticket, endpoint, and delivery-state transaction. */
    fun send(claim: ClaimedWebhookDelivery): WebhookTransportResult
}

/**
 * A RestClient backed by a per-delivery Apache DNS resolver. Apache only receives the addresses
 * accepted by WebhookTargetValidator, so it cannot perform a second hostname lookup after policy
 * validation. The URI host is retained for TLS hostname verification while the resolver pins I/O.
 */
@Component
internal class RestClientWebhookDeliveryTransport(
    private val properties: WebhookDeliveryProperties,
    private val signer: WebhookSignatureSigner = WebhookSignatureSigner(),
    private val targetValidator: WebhookTargetValidator = WebhookTargetValidator(),
    private val clock: Clock,
) : WebhookDeliveryTransport {
    override fun send(claim: ClaimedWebhookDelivery): WebhookTransportResult {
        val target = try {
            targetValidator.validate(claim.endpointUrl, claim.targetPolicy)
        } catch (exception: WebhookTargetRejectedException) {
            throw WebhookTransportException(false, safeCategory(exception.message ?: "TARGET_REJECTED"), exception)
        }
        val requestFactory = requestFactory(target.hostname, target.addresses)
        val body = claim.rawBody
        val timestamp = Instant.now(clock)
        try {
            val result = RestClient.builder().requestFactory(requestFactory).build().post()
                .uri(target.uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Deskseed-Event-Id", claim.eventId.toString())
                .header("X-Deskseed-Delivery-Id", claim.deliveryId.toString())
                .header("X-Deskseed-Timestamp", timestamp.epochSecond.toString())
                .header("X-Deskseed-Signature", signer.sign(claim.secret.toByteArray(StandardCharsets.UTF_8), timestamp, body))
                .header("X-Deskseed-Event-Type", claim.eventType)
                .body(body)
                .exchange { _, response ->
                    WebhookTransportResult(response.statusCode.value(), parseRetryAfter(response.headers.getFirst("Retry-After")))
                }
            return result
        } catch (exception: ResourceAccessException) {
            throw WebhookTransportException(true, "NETWORK_FAILURE", exception)
        } catch (exception: WebhookTransportException) {
            throw exception
        } catch (exception: RuntimeException) {
            throw WebhookTransportException(true, "HTTP_TRANSPORT_FAILURE", exception)
        } finally {
            requestFactory.destroy()
        }
    }

    private fun requestFactory(hostname: String, addresses: List<InetAddress>): HttpComponentsClientHttpRequestFactory {
        val connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(PinnedDnsResolver(hostname, addresses))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeoutMillis))
                    .setSocketTimeout(Timeout.ofMilliseconds(properties.readTimeoutMillis))
                    .build(),
            )
            .build()
        val requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.connectTimeoutMillis))
            .setResponseTimeout(Timeout.ofMilliseconds(properties.readTimeoutMillis))
            .build()
        val client = HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .disableRedirectHandling()
            .build()
        return HttpComponentsClientHttpRequestFactory(client)
    }

    private fun parseRetryAfter(value: String?): Duration? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        if (seconds !in 1..properties.retryMaxSeconds) return null
        return Duration.ofSeconds(seconds)
    }

    private class PinnedDnsResolver(
        private val hostname: String,
        private val addresses: List<InetAddress>,
    ) : DnsResolver {
        override fun resolve(host: String): Array<InetAddress> {
            if (!host.equals(hostname, ignoreCase = true)) throw UnknownHostException(host)
            return addresses.toTypedArray()
        }

        override fun resolveCanonicalHostname(host: String): String {
            if (!host.equals(hostname, ignoreCase = true)) throw UnknownHostException(host)
            return hostname
        }
    }

    private fun safeCategory(value: String): String = value.takeIf {
        it.length in 1..80 && it.all { character -> character.isUpperCase() || character.isDigit() || character == '_' }
    } ?: "TARGET_REJECTED"
}

@Service
internal class WebhookDeliveryClaimService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val secretCipher: WebhookSecretCipher,
    private val properties: WebhookDeliveryProperties,
    private val clock: Clock,
) {
    @Transactional
    fun claimNext(workerId: String): ClaimedWebhookDelivery? {
        require(workerId.matches(WORKER_ID))
        properties.validate()
        val now = Instant.now(clock)
        val candidate = jdbc.query(
            """
            select delivery.*, endpoint.url as endpoint_url, endpoint.target_class, endpoint.allowed_hostnames_json,
                   endpoint.allowed_ports_json, endpoint.allowed_cidrs_json, endpoint.health_state,
                   secret.id as secret_id, secret.ciphertext, secret.nonce, secret.key_version
              from webhook_deliveries delivery
              join webhook_endpoints endpoint on endpoint.id = delivery.endpoint_id
              join webhook_endpoint_secrets secret on secret.endpoint_id = endpoint.id and secret.status = 'ACTIVE'
             where delivery.status in ('PENDING', 'RETRY_SCHEDULED')
               and delivery.next_attempt_at <= ?
               and endpoint.enabled = true and endpoint.deactivated_at is null and endpoint.archived_at is null
               and (endpoint.health_state <> 'OPEN' or endpoint.cooldown_until <= ?)
               and (endpoint.health_state <> 'HALF_OPEN' or endpoint.half_open_claimed = false)
               and endpoint.active_delivery_count < ?
             order by delivery.next_attempt_at, delivery.created_at, delivery.id
             for update of delivery, endpoint skip locked
             limit 1
            """.trimIndent(),
            { row, _ -> row.toCandidate() },
            Timestamp.from(now), Timestamp.from(now), properties.perEndpointConcurrency,
        ).singleOrNull() ?: return null
        val secret = secretCipher.decrypt(candidate.encryptedSecret, candidate.secretId)
        val leaseExpiresAt = now.plusSeconds(properties.leaseSeconds)
        val halfOpen = candidate.healthState != WebhookHealthState.CLOSED
        jdbc.update(
            """
            update webhook_endpoints
               set active_delivery_count = active_delivery_count + 1,
                   health_state = case when health_state = 'OPEN' then 'HALF_OPEN' else health_state end,
                   half_open_claimed = ?, updated_at = ?
             where id = ?
            """.trimIndent(),
            halfOpen, Timestamp.from(now), candidate.endpointId,
        )
        jdbc.update(
            """
            update webhook_deliveries
               set status = 'IN_FLIGHT', attempt_count = attempt_count + 1, next_attempt_at = null,
                   lease_owner = ?, lease_expires_at = ?, error_category = null, updated_at = ?, version = version + 1
             where id = ? and status in ('PENDING', 'RETRY_SCHEDULED')
            """.trimIndent(),
            workerId, Timestamp.from(leaseExpiresAt), Timestamp.from(now), candidate.deliveryId,
        ).also { require(it == 1) }
        val attemptNumber = candidate.attemptCount + 1
        jdbc.update(
            """
            insert into webhook_delivery_attempts (
                id, delivery_id, attempt_number, request_timestamp, response_status, response_headers_json,
                response_summary, latency_millis, error_category, completed_at
            ) values (?, ?, ?, ?, null, '{}', null, null, null, null)
            """.trimIndent(),
            UUID.randomUUID(), candidate.deliveryId, attemptNumber, Timestamp.from(now),
        )
        return ClaimedWebhookDelivery(
            candidate.deliveryId, candidate.endpointId, candidate.eventId, candidate.eventType,
            candidate.payloadJson.toByteArray(StandardCharsets.UTF_8), attemptNumber, now, workerId,
            candidate.endpointUrl, candidate.targetPolicy, secret,
        )
    }

    @Transactional
    fun recoverExpiredLeases(): Int {
        var recovered = 0
        repeat(properties.batchSize.coerceIn(1, 1_000)) {
            val now = Instant.now(clock)
            val expired = jdbc.query(
                """
                select delivery.id, delivery.endpoint_id, delivery.attempt_count, endpoint.health_state
                  from webhook_deliveries delivery
                  join webhook_endpoints endpoint on endpoint.id = delivery.endpoint_id
                 where delivery.status = 'IN_FLIGHT' and delivery.lease_expires_at <= ?
                 order by delivery.lease_expires_at, delivery.id
                 for update of delivery, endpoint skip locked
                 limit 1
                """.trimIndent(),
                { row, _ -> ExpiredLease(row.getObject(1, UUID::class.java), row.getObject(2, UUID::class.java), row.getInt(3), WebhookHealthState.valueOf(row.getString(4))) },
                Timestamp.from(now),
            ).singleOrNull() ?: return recovered
            jdbc.update(
                """update webhook_delivery_attempts set error_category = 'WORKER_LEASE_EXPIRED', completed_at = ?
                     where delivery_id = ? and attempt_number = ? and completed_at is null""",
                Timestamp.from(now), expired.deliveryId, expired.attemptNumber,
            )
            jdbc.update(
                """update webhook_deliveries set status = 'RETRY_SCHEDULED', next_attempt_at = ?, lease_owner = null,
                     lease_expires_at = null, error_category = 'WORKER_LEASE_EXPIRED', updated_at = ?, version = version + 1
                     where id = ? and status = 'IN_FLIGHT'""",
                Timestamp.from(now), Timestamp.from(now), expired.deliveryId,
            )
            jdbc.update(
                """update webhook_endpoints set active_delivery_count = greatest(0, active_delivery_count - 1),
                     health_state = case when health_state = 'HALF_OPEN' then 'OPEN' else health_state end,
                     half_open_claimed = false,
                     cooldown_until = case when health_state = 'HALF_OPEN' then ? else cooldown_until end,
                     updated_at = ? where id = ?""",
                Timestamp.from(now.plusSeconds(properties.circuitCooldownSeconds)), Timestamp.from(now), expired.endpointId,
            )
            recovered += 1
        }
        return recovered
    }

    private fun java.sql.ResultSet.toCandidate() = DeliveryCandidate(
        deliveryId = getObject("id", UUID::class.java),
        endpointId = getObject("endpoint_id", UUID::class.java),
        eventId = getObject("event_id", UUID::class.java),
        eventType = getString("event_type"),
        payloadJson = getString("payload_json"),
        attemptCount = getInt("attempt_count"),
        endpointUrl = getString("endpoint_url"),
        targetPolicy = WebhookTargetPolicy.fromStored(
            WebhookTargetClass.valueOf(getString("target_class")),
            objectMapper.readValue(getString("allowed_hostnames_json"), Array<String>::class.java).toSet(),
            objectMapper.readValue(getString("allowed_ports_json"), Array<Int>::class.java).toSet(),
            objectMapper.readValue(getString("allowed_cidrs_json"), Array<String>::class.java).toSet(),
        ),
        healthState = WebhookHealthState.valueOf(getString("health_state")),
        secretId = getObject("secret_id", UUID::class.java),
        encryptedSecret = EncryptedWebhookSecret(getBytes("ciphertext"), getBytes("nonce"), getString("key_version")),
    )

    private data class DeliveryCandidate(
        val deliveryId: UUID,
        val endpointId: UUID,
        val eventId: UUID,
        val eventType: String,
        val payloadJson: String,
        val attemptCount: Int,
        val endpointUrl: String,
        val targetPolicy: WebhookTargetPolicy,
        val healthState: WebhookHealthState,
        val secretId: UUID,
        val encryptedSecret: EncryptedWebhookSecret,
    )

    private data class ExpiredLease(val deliveryId: UUID, val endpointId: UUID, val attemptNumber: Int, val healthState: WebhookHealthState)

    private companion object { val WORKER_ID = Regex("[A-Za-z0-9._:-]{1,100}") }
}

@Service
internal class WebhookDeliveryFinalizer(
    private val jdbc: JdbcTemplate,
    private val properties: WebhookDeliveryProperties,
    private val clock: Clock,
) {
    @Transactional
    fun complete(claim: ClaimedWebhookDelivery, result: Result<WebhookTransportResult>) {
        val now = Instant.now(clock)
        val state = jdbc.query(
            """
            select delivery.*, endpoint.health_state, endpoint.consecutive_failures
              from webhook_deliveries delivery
              join webhook_endpoints endpoint on endpoint.id = delivery.endpoint_id
             where delivery.id = ? and delivery.status = 'IN_FLIGHT' and delivery.lease_owner = ?
             for update of delivery, endpoint
            """.trimIndent(),
            { row, _ -> FinalizationState(row.getObject("id", UUID::class.java), row.getObject("endpoint_id", UUID::class.java), row.getInt("attempt_count"), WebhookHealthState.valueOf(row.getString("health_state")), row.getInt("consecutive_failures")) },
            claim.deliveryId, claim.leaseOwner,
        ).singleOrNull() ?: return
        val outcome = result.fold(
            onSuccess = { response -> outcomeFor(response) },
            onFailure = { failure ->
                val safe = failure as? WebhookTransportException ?: WebhookTransportException(true, "UNEXPECTED_TRANSPORT_FAILURE", failure)
                DeliveryOutcome(false, safe.retryable, safe.category, null, null)
            },
        )
        val latency = max(0, Duration.between(claim.claimedAt, now).toMillis())
        jdbc.update(
            """update webhook_delivery_attempts set response_status = ?, response_headers_json = cast(? as jsonb), response_summary = ?,
                     latency_millis = ?, error_category = ?, completed_at = ?
                 where delivery_id = ? and attempt_number = ? and completed_at is null""",
            outcome.responseStatus, objectMapperHeaders(outcome.retryAfter), outcome.responseStatus?.let { "HTTP $it" }, latency,
            if (outcome.success) null else outcome.category, Timestamp.from(now), state.deliveryId, state.attemptNumber,
        )
        if (outcome.success) {
            jdbc.update(
                """update webhook_deliveries set status = 'SUCCEEDED', next_attempt_at = null, lease_owner = null, lease_expires_at = null,
                     error_category = null, completed_at = ?, updated_at = ?, version = version + 1 where id = ?""",
                Timestamp.from(now), Timestamp.from(now), state.deliveryId,
            )
            closeCircuit(state.endpointId, now)
            return
        }
        val retry = outcome.retryable && state.attemptNumber < properties.maxAttempts
        val newFailures = state.consecutiveFailures + if (outcome.retryable) 1 else 0
        val openCircuit = outcome.retryable && newFailures >= properties.circuitFailureThreshold
        val retryAt = if (retry) {
            val requested = outcome.retryAfter ?: backoff(state.deliveryId, state.attemptNumber)
            val cooldown = if (openCircuit) Duration.ofSeconds(properties.circuitCooldownSeconds) else Duration.ZERO
            now.plus(if (requested > cooldown) requested else cooldown)
        } else null
        jdbc.update(
            """update webhook_deliveries set status = ?, next_attempt_at = ?, lease_owner = null, lease_expires_at = null,
                     error_category = ?, completed_at = ?, updated_at = ?, version = version + 1 where id = ?""",
            if (retry) "RETRY_SCHEDULED" else "DEAD_LETTERED", retryAt?.let(Timestamp::from), outcome.category,
            if (retry) null else Timestamp.from(now), Timestamp.from(now), state.deliveryId,
        )
        jdbc.update(
            """update webhook_endpoints set active_delivery_count = greatest(0, active_delivery_count - 1),
                     health_state = ?, cooldown_until = ?, consecutive_failures = ?, half_open_claimed = false,
                     last_failed_at = ?, updated_at = ? where id = ?""",
            if (openCircuit) "OPEN" else if (state.healthState == WebhookHealthState.HALF_OPEN) "CLOSED" else state.healthState.name,
            if (openCircuit) Timestamp.from(now.plusSeconds(properties.circuitCooldownSeconds)) else null,
            if (openCircuit || outcome.retryable) newFailures else state.consecutiveFailures,
            Timestamp.from(now), Timestamp.from(now), state.endpointId,
        )
    }

    private fun closeCircuit(endpointId: UUID, now: Instant) {
        jdbc.update(
            """update webhook_endpoints set active_delivery_count = greatest(0, active_delivery_count - 1), health_state = 'CLOSED',
                     cooldown_until = null, consecutive_failures = 0, half_open_claimed = false,
                     last_succeeded_at = ?, updated_at = ? where id = ?""",
            Timestamp.from(now), Timestamp.from(now), endpointId,
        )
    }

    private fun outcomeFor(response: WebhookTransportResult): DeliveryOutcome = when (response.status) {
        in 200..299 -> DeliveryOutcome(true, false, "HTTP_SUCCESS", response.status, response.retryAfter)
        408, 429 -> DeliveryOutcome(false, true, "HTTP_${response.status}", response.status, response.retryAfter)
        in 500..599 -> DeliveryOutcome(false, true, "HTTP_${response.status}", response.status, response.retryAfter)
        else -> DeliveryOutcome(false, false, "HTTP_${response.status}", response.status, response.retryAfter)
    }

    private fun backoff(deliveryId: UUID, attemptNumber: Int): Duration {
        val exponent = min(attemptNumber - 1, 20)
        val seconds = min(properties.retryMaxSeconds, properties.retryBaseSeconds * (1L shl exponent))
        val jitterMillis = (deliveryId.leastSignificantBits and 0x3ff).toLong()
        return Duration.ofSeconds(seconds).plusMillis(jitterMillis)
    }

    private fun objectMapperHeaders(retryAfter: Duration?): String = retryAfter?.seconds?.let { "{\"retryAfterSeconds\":$it}" } ?: "{}"
    private data class DeliveryOutcome(val success: Boolean, val retryable: Boolean, val category: String, val responseStatus: Int?, val retryAfter: Duration?)
    private data class FinalizationState(val deliveryId: UUID, val endpointId: UUID, val attemptNumber: Int, val healthState: WebhookHealthState, val consecutiveFailures: Int)
}

@Component
internal class WebhookDeliveryWorker(
    private val claimService: WebhookDeliveryClaimService,
    private val finalizer: WebhookDeliveryFinalizer,
    private val transport: WebhookDeliveryTransport,
    private val properties: WebhookDeliveryProperties,
    meterRegistry: MeterRegistry,
) {
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val succeeded = Counter.builder("deskseed.webhook.delivery.succeeded").register(meterRegistry)
    private val failed = Counter.builder("deskseed.webhook.delivery.failed").register(meterRegistry)

    fun runDueBatch(workerId: String = "webhook-delivery"): Int {
        properties.validate()
        val claims = buildList {
            repeat(min(properties.batchSize, properties.globalConcurrency)) {
                val claim = claimService.claimNext("$workerId-$it") ?: return@repeat
                add(claim)
            }
        }
        claims.map { claim ->
            executor.submit {
                val outcome = runCatching { transport.send(claim) }
                finalizer.complete(claim, outcome)
                if (outcome.isSuccess) succeeded.increment() else failed.increment()
            }
        }.forEach { it.get() }
        return claims.size
    }

    fun recoverExpiredLeases(): Int = claimService.recoverExpiredLeases()

    @PreDestroy
    fun close() {
        executor.close()
    }
}

@Component
@ConditionalOnProperty(prefix = "deskseed.webhook.delivery", name = ["enabled", "scheduling-enabled"], havingValue = "true")
internal class WebhookDeliveryScheduler(private val worker: WebhookDeliveryWorker) {
    @Scheduled(fixedDelayString = "\${deskseed.webhook.delivery.fixed-delay-ms:1000}")
    fun deliverDue() {
        worker.runDueBatch()
        worker.recoverExpiredLeases()
    }
}

@Component
internal class WebhookDeliveryMetrics(jdbc: JdbcTemplate, meterRegistry: MeterRegistry) {
    init {
        Gauge.builder("deskseed.webhook.delivery.backlog") {
            jdbc.queryForObject("select count(*) from webhook_deliveries where status in ('PENDING', 'RETRY_SCHEDULED', 'IN_FLIGHT')", Long::class.java) ?: 0L
        }.register(meterRegistry)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookDeliveryProperties::class)
internal class WebhookDeliveryConfiguration

private fun WebhookTargetPolicy.Companion.fromStored(
    targetClass: WebhookTargetClass,
    hostnames: Set<String>,
    ports: Set<Int>,
    cidrs: Set<String>,
): WebhookTargetPolicy = when (targetClass) {
    WebhookTargetClass.PUBLIC -> WebhookTargetPolicy.publicDefault()
    WebhookTargetClass.PRIVATE_APPROVED -> WebhookTargetPolicy.privateApproved(hostnames.single(), ports.single(), cidrs)
}
