package dev.deskseed.customerauth.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

internal data class MagicLinkRateDecision(
    val allowed: Boolean,
    val destinationFingerprint: String,
    val networkFingerprint: String,
)

@Component
internal class CustomerMagicLinkRateLimiter(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: CustomerAuthProperties,
    private val clock: Clock,
) {
    fun acquire(emailNormalized: String, remoteAddress: String): MagicLinkRateDecision {
        val destination = CustomerAuthSecrets.fingerprint(properties.fingerprintKey, "destination:$emailNormalized")
        val network = CustomerAuthSecrets.fingerprint(properties.fingerprintKey, "network:$remoteAddress")
        jdbcTemplate.queryForObject(
            "select pg_advisory_xact_lock(hashtextextended(?, 0))",
            { _, _ -> Unit },
            "$destination:$network",
        )
        val now = Instant.now(clock)
        val current = jdbcTemplate.query(
            """
            select request_count, window_started_at
              from customer_magic_link_request_limits
             where destination_fingerprint = ? and network_fingerprint = ?
            """.trimIndent(),
            { resultSet, _ -> resultSet.getInt(1) to resultSet.getTimestamp(2).toInstant() },
            destination,
            network,
        ).singleOrNull()
        val activeWindow = current?.takeIf { it.second.plus(properties.requestWindow).isAfter(now) }
        val nextCount = activeWindow?.first?.plus(1) ?: 1
        val windowStartedAt = activeWindow?.second ?: now
        val allowed = nextCount <= properties.requestLimit
        jdbcTemplate.update(
            """
            insert into customer_magic_link_request_limits
                (destination_fingerprint, network_fingerprint, request_count, window_started_at, locked_until, updated_at)
            values (?, ?, ?, ?, ?, ?)
            on conflict (destination_fingerprint, network_fingerprint) do update set
                request_count = excluded.request_count,
                window_started_at = excluded.window_started_at,
                locked_until = excluded.locked_until,
                updated_at = excluded.updated_at
            """.trimIndent(),
            destination,
            network,
            nextCount,
            Timestamp.from(windowStartedAt),
            if (allowed) null else Timestamp.from(windowStartedAt.plus(properties.requestWindow)),
            Timestamp.from(now),
        )
        return MagicLinkRateDecision(allowed, destination, network)
    }
}
