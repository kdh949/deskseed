package dev.deskseed.platformapi.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil

internal data class PlatformRateDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val resetAtEpochSecond: Long,
    val retryAfterSeconds: Long,
)

@Component
internal class PlatformRateLimiter(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${deskseed.platform.rate-limit.requests-per-minute:60}") private val limit: Int,
) {
    init {
        require(limit > 0) { "Platform rate limit must be positive" }
    }

    /**
     * This commits quota in its own transaction before the platform command. A later
     * command rollback still consumes the request budget; a shared-store failure is
     * surfaced as 503 rather than allowing an in-memory quota bypass.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun consume(clientId: UUID): PlatformRateDecision = try {
        val consumed = jdbcTemplate.queryForObject(
            """
            with database_clock as (
                select clock_timestamp() as now_at
            ), current_window as (
                select now_at,
                       date_trunc('minute', now_at) as window_started_at,
                       date_trunc('minute', now_at) + interval '1 minute' as reset_at
                from database_clock
            ), consumed as (
                insert into platform_rate_limit_buckets
                    (client_id, window_started_at, request_count, expires_at, updated_at)
                select ?, window_started_at, 1, reset_at, now_at
                from current_window
                on conflict (client_id, window_started_at) do update set
                    request_count = platform_rate_limit_buckets.request_count + 1,
                    expires_at = excluded.expires_at,
                    updated_at = excluded.updated_at
                returning request_count
            )
            select consumed.request_count, current_window.now_at, current_window.reset_at
            from consumed cross join current_window
            """.trimIndent(),
            { result, _ ->
                PlatformRateBucketConsumption(
                    requestCount = result.getInt("request_count"),
                    now = result.getTimestamp("now_at").toInstant(),
                    resetAt = result.getTimestamp("reset_at").toInstant(),
                )
            },
            clientId,
        )
        PlatformRateDecision(
            allowed = consumed.requestCount <= limit,
            limit = limit,
            remaining = (limit - consumed.requestCount).coerceAtLeast(0),
            resetAtEpochSecond = consumed.resetAt.epochSecond,
            retryAfterSeconds = retryAfter(consumed.now, consumed.resetAt),
        )
    } catch (exception: DataAccessException) {
        throw PlatformRateLimitUnavailableException(exception)
    } catch (exception: IllegalStateException) {
        throw PlatformRateLimitUnavailableException(exception)
    }

    private fun retryAfter(now: Instant, resetAt: Instant): Long = ceil(
        Duration.between(now, resetAt).toMillis().coerceAtLeast(1) / 1_000.0,
    ).toLong().coerceAtLeast(1)

}

private data class PlatformRateBucketConsumption(
    val requestCount: Int,
    val now: Instant,
    val resetAt: Instant,
)

@Service
internal class PlatformRateLimitRetentionJob(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${deskseed.platform.rate-limit.cleanup-batch-size:500}") private val cleanupBatchSize: Int,
) {
    init {
        require(cleanupBatchSize > 0) { "Platform rate-limit cleanup batch size must be positive" }
    }

    @Scheduled(
        fixedDelayString = "\${deskseed.platform.rate-limit.cleanup-interval:1h}",
        initialDelayString = "\${deskseed.platform.rate-limit.cleanup-initial-delay:10m}",
    )
    @Transactional
    fun purgeExpiredScheduled() {
        purgeExpired(databaseNow())
    }

    @Transactional
    fun purgeExpired(now: Instant): Int = jdbcTemplate.queryForObject(
        """
        with eligible as (
            select client_id, window_started_at
            from platform_rate_limit_buckets
            where expires_at <= ?
            order by expires_at, client_id, window_started_at
            limit ?
            for update skip locked
        ), deleted as (
            delete from platform_rate_limit_buckets bucket
            using eligible
            where bucket.client_id = eligible.client_id
              and bucket.window_started_at = eligible.window_started_at
            returning bucket.client_id
        )
        select count(*) from deleted
        """.trimIndent(),
        Int::class.java,
        Timestamp.from(now),
        cleanupBatchSize,
    ) ?: 0

    private fun databaseNow(): Instant = jdbcTemplate.queryForObject(
        "select clock_timestamp()",
        { result, _ -> result.getTimestamp(1).toInstant() },
    ) ?: throw IllegalStateException("Platform rate-limit database clock returned no value")
}

/** Raised instead of bypassing quota whenever the shared bucket cannot be persisted. */
internal class PlatformRateLimitUnavailableException(cause: Throwable) : RuntimeException(cause)
