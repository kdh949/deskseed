package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException

@dev.deskseed.testsupport.category.FastTest
class RedisAuthenticationAttemptLimiterTest {
    private val meterRegistry = SimpleMeterRegistry()

    @Test
    fun `records bounded allow and deny decisions with latency histograms`() {
        val decisions = ArrayDeque(listOf("ALLOW:0", "DENY:1000"))
        val limiter = RedisAuthenticationAttemptLimiter(
            RedisAuthenticationRateLimitCommand { _, _ -> decisions.removeFirst() },
            CustomerAuthProperties(),
            meterRegistry,
        )
        val attempt = attempt()

        assertThat(limiter.acquire(attempt).allowed).isTrue()
        assertThat(limiter.acquire(attempt).allowed).isFalse()

        assertThat(counter("allowed")).isEqualTo(1.0)
        assertThat(counter("denied")).isEqualTo(1.0)
        assertThat(timer("allowed")).isEqualTo(1L)
        assertThat(timer("denied")).isEqualTo(1L)
        val tagKeys = meterRegistry.meters.flatMap { it.id.tags }.map { it.key }.toSet()
        assertThat(tagKeys).contains("purpose", "outcome")
        assertThat(tagKeys).doesNotContain(
            "destinationFingerprint",
            "requesterNetworkFingerprint",
            "requestId",
            "correlationId",
        )
    }

    @Test
    fun `redis connection failure becomes the generic fail closed exception`() {
        val command = RedisAuthenticationRateLimitCommand { _, _ ->
            throw RedisConnectionFailureException("synthetic unavailable")
        }
        val limiter = RedisAuthenticationAttemptLimiter(command, CustomerAuthProperties(), meterRegistry)

        assertThatThrownBy {
            limiter.acquire(
                AuthenticationAttempt(
                    purpose = CustomerAuthenticationPurpose.MAGIC_LINK_REQUEST,
                    destinationFingerprint = "a".repeat(64),
                    requesterNetworkFingerprint = "b".repeat(64),
                ),
            )
        }.isInstanceOf(AuthenticationAttemptLimiterUnavailableException::class.java)
            .hasMessage("customer authentication limiter is unavailable")
            .hasNoCause()

        assertThat(counter("unavailable")).isEqualTo(1.0)
        assertThat(timer("unavailable")).isEqualTo(1L)
    }

    private fun attempt() = AuthenticationAttempt(
        purpose = CustomerAuthenticationPurpose.MAGIC_LINK_REQUEST,
        destinationFingerprint = "a".repeat(64),
        requesterNetworkFingerprint = "b".repeat(64),
    )

    private fun counter(outcome: String) = meterRegistry
        .get("deskseed.customer.auth.limiter.decisions")
        .tags("purpose", "magic-link-request", "outcome", outcome)
        .counter()
        .count()

    private fun timer(outcome: String) = meterRegistry
        .get("deskseed.customer.auth.limiter.duration")
        .tags("purpose", "magic-link-request", "outcome", outcome)
        .timer()
        .count()
}
