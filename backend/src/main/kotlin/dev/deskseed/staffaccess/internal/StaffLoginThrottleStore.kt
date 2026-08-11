package dev.deskseed.staffaccess.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@Repository
internal class StaffLoginThrottleStore(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun lockedFor(emailFingerprint: String, networkFingerprint: String, now: Instant): Duration? {
        val lockedUntil = jdbcTemplate.query(
            """
            select locked_until
            from staff_login_throttles
            where email_fingerprint = ? and network_fingerprint = ?
            """.trimIndent(),
            { result, _ -> result.getTimestamp("locked_until")?.toInstant() },
            emailFingerprint,
            networkFingerprint,
        ).firstOrNull()
        return lockedUntil?.takeIf { it.isAfter(now) }?.let { Duration.between(now, it) }
    }

    fun registerFailure(
        emailFingerprint: String,
        networkFingerprint: String,
        now: Instant,
        window: Duration,
        failureLimit: Int,
    ): Duration? {
        jdbcTemplate.update(
            """
            insert into staff_login_throttles
                (email_fingerprint, network_fingerprint, failure_count,
                 window_started_at, locked_until, updated_at)
            values (?, ?, 0, ?, null, ?)
            on conflict (email_fingerprint, network_fingerprint) do nothing
            """.trimIndent(),
            emailFingerprint,
            networkFingerprint,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        val current = jdbcTemplate.queryForMap(
            """
            select failure_count, window_started_at, locked_until
            from staff_login_throttles
            where email_fingerprint = ? and network_fingerprint = ?
            for update
            """.trimIndent(),
            emailFingerprint,
            networkFingerprint,
        )
        val currentWindow = (current["window_started_at"] as Timestamp).toInstant()
        val stillInWindow = currentWindow.plus(window).isAfter(now)
        val nextWindow = if (stillInWindow) currentWindow else now
        val nextCount = if (stillInWindow) (current["failure_count"] as Number).toInt() + 1 else 1
        val lockedUntil = if (nextCount >= failureLimit) nextWindow.plus(window) else null

        jdbcTemplate.update(
            """
            update staff_login_throttles
            set failure_count = ?, window_started_at = ?, locked_until = ?, updated_at = ?
            where email_fingerprint = ? and network_fingerprint = ?
            """.trimIndent(),
            nextCount,
            Timestamp.from(nextWindow),
            lockedUntil?.let(Timestamp::from),
            Timestamp.from(now),
            emailFingerprint,
            networkFingerprint,
        )
        return lockedUntil?.let { Duration.between(now, it) }
    }

    fun clear(emailFingerprint: String, networkFingerprint: String) {
        jdbcTemplate.update(
            "delete from staff_login_throttles where email_fingerprint = ? and network_fingerprint = ?",
            emailFingerprint,
            networkFingerprint,
        )
    }
}
