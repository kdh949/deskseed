package dev.deskseed.portal.internal

import dev.deskseed.integration.IntegrationNetworkPolicy
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil

@ConfigurationProperties("deskseed.portal.public-request-rate-limit")
internal data class PublicRequestRateLimitProperties(
    var enabled: Boolean = true,
    var window: Duration = Duration.ofMinutes(1),
    var destinationLimit: Int = 5,
    var clientLimit: Int = 100,
    var globalLimit: Int = 1_000,
    var fingerprintKey: String = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc=",
    var trustedProxyCidrs: String = "127.0.0.0/8,::1/128",
    var maxForwardedHops: Int = 10,
    var cleanupBatchSize: Int = 500,
    var cleanupInterval: Duration = Duration.ofHours(1),
    var cleanupInitialDelay: Duration = Duration.ofMinutes(10),
) : InitializingBean {
    override fun afterPropertiesSet() = validate()

    fun validate() {
        require(!window.isZero && !window.isNegative && window.nano == 0) {
            "Public request rate-limit window must be a positive whole number of seconds"
        }
        require(destinationLimit > 0) { "Public request destination limit must be positive" }
        require(clientLimit > 0) { "Public request client limit must be positive" }
        require(globalLimit > 0) { "Public request global limit must be positive" }
        require(maxForwardedHops in 1..10) { "Public request forwarded hop bound must be between 1 and 10" }
        require(cleanupBatchSize > 0) { "Public request rate-limit cleanup batch size must be positive" }
        require(!cleanupInterval.isZero && !cleanupInterval.isNegative) {
            "Public request rate-limit cleanup interval must be positive"
        }
        require(!cleanupInitialDelay.isNegative) {
            "Public request rate-limit cleanup initial delay cannot be negative"
        }
        fingerprintKeyBytes()
    }

    fun trustedProxyNetworks(): List<String> = trustedProxyCidrs
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)

    fun fingerprintKeyBytes(): ByteArray = runCatching { Base64.getDecoder().decode(fingerprintKey) }
        .getOrElse { throw IllegalArgumentException("Public request rate-limit fingerprint key must be valid Base64") }
        .also { require(it.size >= 32) { "Public request rate-limit fingerprint key must contain at least 32 bytes" } }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublicRequestRateLimitProperties::class)
internal class PublicRequestRateLimitConfiguration

internal class PublicRequestRateLimitExceededException(
    val retryAfter: Duration,
) : RuntimeException()

internal class PublicRequestRateLimitUnavailableException : RuntimeException()

internal class PublicRequestNetworkBoundaryException : RuntimeException()

@Component
internal class PublicRequestClientAddressResolver(
    private val addressPolicy: IntegrationNetworkPolicy,
    private val properties: PublicRequestRateLimitProperties,
) {
    init {
        addressPolicy.validateCidrs(properties.trustedProxyNetworks())
    }

    fun resolve(request: HttpServletRequest): String {
        val peer = addressPolicy.normalizeLiteral(request.remoteAddr)
            ?: throw PublicRequestNetworkBoundaryException()
        if (!addressPolicy.isAllowed(peer, properties.trustedProxyNetworks())) return peer

        val forwardedHeaders = request.getHeaders(FORWARDED_FOR).asSequence().toList()
        if (forwardedHeaders.isEmpty() || forwardedHeaders.singleOrNull().isNullOrBlank()) return peer
        if (forwardedHeaders.size != 1) throw PublicRequestNetworkBoundaryException()

        val chain = forwardedHeaders.single().split(',').map(String::trim)
        if (chain.isEmpty() || chain.size > properties.maxForwardedHops || chain.any(String::isEmpty)) {
            throw PublicRequestNetworkBoundaryException()
        }
        val normalized = chain.map { addressPolicy.normalizeLiteral(it) ?: throw PublicRequestNetworkBoundaryException() }
        return normalized.asReversed().firstOrNull { !addressPolicy.isAllowed(it, properties.trustedProxyNetworks()) }
            ?: normalized.first()
    }

    private companion object {
        const val FORWARDED_FOR = "X-Forwarded-For"
    }
}

@Service
internal class PublicRequestRateLimiter(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: PublicRequestRateLimitProperties,
) {
    private val fingerprintKey = SecretKeySpec(properties.fingerprintKeyBytes(), "HmacSHA256")

    /**
     * This transaction intentionally commits before customer/ticket creation. A later ticket transaction rollback still
     * consumes the anti-abuse budget, while limiter storage failures fail closed before a customer can be created.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun consume(destination: String, clientAddress: String) {
        if (!properties.enabled) return
        try {
            val now = databaseNow()
            val windowStartedAt = fixedWindowStart(now)
            val resetAt = windowStartedAt.plus(properties.window)
            val retryAfter = retryAfter(now, resetAt)
            val buckets = listOf(
                PublicRequestRateLimitBucket(BucketType.GLOBAL, fingerprint(BucketType.GLOBAL, "public-request-v1"), properties.globalLimit),
                PublicRequestRateLimitBucket(BucketType.CLIENT, fingerprint(BucketType.CLIENT, clientAddress), properties.clientLimit),
                PublicRequestRateLimitBucket(
                    BucketType.DESTINATION,
                    fingerprint(BucketType.DESTINATION, destination.trim().lowercase(Locale.ROOT)),
                    properties.destinationLimit,
                ),
            )
            buckets.forEach { bucket ->
                if (increment(bucket, windowStartedAt, resetAt, now) > bucket.limit) {
                    throw PublicRequestRateLimitExceededException(retryAfter)
                }
            }
        } catch (exception: PublicRequestRateLimitExceededException) {
            throw exception
        } catch (exception: DataAccessException) {
            throw PublicRequestRateLimitUnavailableException()
        }
    }

    /** The rate-limit bucket key is shared across nodes, so its time source must be shared as well. */
    private fun databaseNow(): Instant = jdbcTemplate.queryForObject(
        "select clock_timestamp()",
        { resultSet, _ -> resultSet.getTimestamp(1).toInstant() },
    ) ?: throw PublicRequestRateLimitUnavailableException()

    private fun increment(
        bucket: PublicRequestRateLimitBucket,
        windowStartedAt: Instant,
        expiresAt: Instant,
        now: Instant,
    ): Int = jdbcTemplate.queryForObject(
        """
        insert into public_request_rate_limit_buckets
            (bucket_type, bucket_fingerprint, window_started_at, request_count, expires_at, updated_at)
        values (?, ?, ?, 1, ?, ?)
        on conflict (bucket_type, bucket_fingerprint, window_started_at) do update set
            request_count = public_request_rate_limit_buckets.request_count + 1,
            expires_at = excluded.expires_at,
            updated_at = excluded.updated_at
        returning request_count
        """.trimIndent(),
        { resultSet, _ -> resultSet.getInt("request_count") },
        bucket.type.name,
        bucket.fingerprint,
        Timestamp.from(windowStartedAt),
        Timestamp.from(expiresAt),
        Timestamp.from(now),
    )

    private fun fixedWindowStart(now: Instant): Instant {
        val windowSeconds = properties.window.seconds
        val epochSecond = now.epochSecond
        return Instant.ofEpochSecond(epochSecond - Math.floorMod(epochSecond, windowSeconds))
    }

    private fun retryAfter(now: Instant, resetAt: Instant): Duration {
        val milliseconds = Duration.between(now, resetAt).toMillis().coerceAtLeast(1)
        return Duration.ofSeconds(ceil(milliseconds / 1_000.0).toLong().coerceAtLeast(1))
    }

    private fun fingerprint(type: BucketType, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(fingerprintKey)
        return mac.doFinal("${type.name}:$value".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

@Service
internal class PublicRequestRateLimitRetentionJob(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: PublicRequestRateLimitProperties,
    private val clock: Clock,
) {
    @Scheduled(
        fixedDelayString = "\${deskseed.portal.public-request-rate-limit.cleanup-interval:1h}",
        initialDelayString = "\${deskseed.portal.public-request-rate-limit.cleanup-initial-delay:10m}",
    )
    @Transactional
    fun purgeExpiredScheduled() {
        if (properties.enabled) purgeExpired(Instant.now(clock))
    }

    @Transactional
    fun purgeExpired(now: Instant): Int = jdbcTemplate.queryForObject(
        """
        with eligible as (
            select bucket_type, bucket_fingerprint, window_started_at
            from public_request_rate_limit_buckets
            where expires_at <= ?
            order by expires_at, bucket_type, bucket_fingerprint, window_started_at
            limit ?
            for update skip locked
        ), deleted as (
            delete from public_request_rate_limit_buckets bucket
            using eligible
            where bucket.bucket_type = eligible.bucket_type
              and bucket.bucket_fingerprint = eligible.bucket_fingerprint
              and bucket.window_started_at = eligible.window_started_at
            returning bucket.bucket_type
        )
        select count(*) from deleted
        """.trimIndent(),
        Int::class.java,
        Timestamp.from(now),
        properties.cleanupBatchSize,
    ) ?: 0
}

private enum class BucketType {
    GLOBAL,
    CLIENT,
    DESTINATION,
}

private data class PublicRequestRateLimitBucket(
    val type: BucketType,
    val fingerprint: String,
    val limit: Int,
)
