package dev.deskseed.platformapi.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal data class PlatformRateDecision(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val resetAtEpochSecond: Long,
    val retryAfterSeconds: Long,
)

@Component
internal class PlatformRateLimiter(
    private val clock: Clock,
    @Value("\${deskseed.platform.rate-limit.requests-per-minute:60}") private val limit: Int,
    @Value("\${deskseed.platform.rate-limit.max-clients:10000}") private val maxClients: Int = 10_000,
    @Value("\${deskseed.platform.rate-limit.stale-after:2m}") private val staleAfter: Duration = Duration.ofMinutes(2),
) {
    private val windows = mutableMapOf<UUID, Window>()
    private var nextCleanupAt = Long.MIN_VALUE

    init {
        require(limit > 0) { "Platform rate limit must be positive" }
        require(maxClients > 0) { "Platform rate-limit client capacity must be positive" }
        require(staleAfter.seconds >= WINDOW_SECONDS) { "Platform rate-limit stale window must be at least one minute" }
    }

    @Synchronized
    fun consume(clientId: UUID): PlatformRateDecision {
        val now = Instant.now(clock).epochSecond
        val windowStart = now - Math.floorMod(now, WINDOW_SECONDS)
        if (now >= nextCleanupAt || windows.size >= maxClients) {
            windows.entries.removeIf { (_, window) -> window.lastSeenAt + staleAfter.seconds <= now }
            nextCleanupAt = now + WINDOW_SECONDS
        }
        val current = windows[clientId]
        if (current == null && windows.size >= maxClients) {
            val retryAt = windows.values.minOf { it.lastSeenAt + staleAfter.seconds }
            return PlatformRateDecision(
                allowed = false,
                limit = limit,
                remaining = 0,
                resetAtEpochSecond = retryAt,
                retryAfterSeconds = (retryAt - now).coerceAtLeast(1),
            )
        }
        val window = if (current == null || current.startedAt != windowStart) {
            Window(windowStart, 0, now).also { windows[clientId] = it }
        } else {
            current.also { it.lastSeenAt = now }
        }
        val reset = windowStart + WINDOW_SECONDS
        if (window.count >= limit) {
            return PlatformRateDecision(false, limit, 0, reset, (reset - now).coerceAtLeast(1))
        }
        window.count += 1
        return PlatformRateDecision(true, limit, limit - window.count, reset, (reset - now).coerceAtLeast(1))
    }

    private data class Window(val startedAt: Long, var count: Int, var lastSeenAt: Long)

    private companion object {
        const val WINDOW_SECONDS = 60L
    }
}
