package dev.deskseed.platformapi.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Duration
import java.util.UUID

class PlatformRateLimiterTest {
    @Test
    fun `limit is isolated per client and returns deterministic reset headers`() {
        val clock = Clock.fixed(Instant.parse("2026-08-12T00:00:30Z"), ZoneOffset.UTC)
        val limiter = PlatformRateLimiter(clock, 2)
        val firstClient = UUID.randomUUID()
        val secondClient = UUID.randomUUID()

        assertThat(limiter.consume(firstClient).remaining).isEqualTo(1)
        assertThat(limiter.consume(firstClient).remaining).isZero()
        val denied = limiter.consume(firstClient)
        assertThat(denied.allowed).isFalse()
        assertThat(denied.retryAfterSeconds).isEqualTo(30)
        assertThat(denied.resetAtEpochSecond).isEqualTo(Instant.parse("2026-08-12T00:01:00Z").epochSecond)
        assertThat(limiter.consume(secondClient).allowed).isTrue()
    }

    @Test
    fun `client windows are capped fail closed and stale entries are evicted`() {
        val clock = MutableClock(Instant.parse("2026-08-12T00:00:00Z"))
        val limiter = PlatformRateLimiter(clock, limit = 2, maxClients = 2, staleAfter = Duration.ofMinutes(2))
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val overflow = UUID.randomUUID()

        assertThat(limiter.consume(first).allowed).isTrue()
        assertThat(limiter.consume(second).allowed).isTrue()
        val denied = limiter.consume(overflow)
        assertThat(denied.allowed).isFalse()
        assertThat(denied.remaining).isZero()

        clock.advance(Duration.ofMinutes(2).plusSeconds(1))
        assertThat(limiter.consume(overflow).allowed).isTrue()
        assertThat(limiter.consume(first).allowed).isTrue()
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }
}
