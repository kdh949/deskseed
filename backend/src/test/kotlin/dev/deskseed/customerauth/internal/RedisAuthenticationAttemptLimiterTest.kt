package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException

@dev.deskseed.testsupport.category.FastTest
class RedisAuthenticationAttemptLimiterTest {
    @Test
    fun `redis connection failure becomes the generic fail closed exception`() {
        val command = RedisAuthenticationRateLimitCommand { _, _ ->
            throw RedisConnectionFailureException("synthetic unavailable")
        }
        val limiter = RedisAuthenticationAttemptLimiter(command, CustomerAuthProperties())

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
    }
}
