package dev.deskseed.platformapi.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
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
) {
    private val windows = mutableMapOf<UUID, Window>()

    init {
        require(limit > 0) { "Platform rate limit must be positive" }
    }

    @Synchronized
    fun consume(clientId: UUID): PlatformRateDecision {
        val now = Instant.now(clock).epochSecond
        val windowStart = now - Math.floorMod(now, WINDOW_SECONDS)
        val current = windows[clientId]
        val window = if (current == null || current.startedAt != windowStart) {
            Window(windowStart, 0).also { windows[clientId] = it }
        } else {
            current
        }
        val reset = windowStart + WINDOW_SECONDS
        if (window.count >= limit) {
            return PlatformRateDecision(false, limit, 0, reset, (reset - now).coerceAtLeast(1))
        }
        window.count += 1
        return PlatformRateDecision(true, limit, limit - window.count, reset, (reset - now).coerceAtLeast(1))
    }

    private data class Window(val startedAt: Long, var count: Int)

    private companion object {
        const val WINDOW_SECONDS = 60L
    }
}
