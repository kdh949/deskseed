package dev.deskseed.platformapi.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
}
