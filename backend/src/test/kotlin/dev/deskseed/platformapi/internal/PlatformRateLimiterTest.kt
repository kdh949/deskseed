package dev.deskseed.platformapi.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** PostgreSQL is the shared state: two independently constructed limiter instances share one quota. */
@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.platform.rate-limit.requests-per-minute=2",
    ],
)
@Testcontainers
class PlatformRateLimiterTest {
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearBuckets() {
        jdbcTemplate.execute("truncate table platform_rate_limit_buckets")
    }

    @Test
    fun `two application instances consume the same PostgreSQL client bucket`() {
        val firstInstance = PlatformRateLimiter(jdbcTemplate, 2)
        val secondInstance = PlatformRateLimiter(jdbcTemplate, 2)
        val clientId = UUID.randomUUID()

        assertThat(firstInstance.consume(clientId)).matches { it.allowed && it.remaining == 1 }
        assertThat(secondInstance.consume(clientId)).matches { it.allowed && it.remaining == 0 }
        val denied = firstInstance.consume(clientId)

        assertThat(denied.allowed).isFalse()
        assertThat(denied.remaining).isZero()
        assertThat(denied.retryAfterSeconds).isBetween(1, 60)
        assertThat(
            jdbcTemplate.queryForObject(
                "select request_count from platform_rate_limit_buckets where client_id = ?",
                Int::class.java,
                clientId,
            ),
        ).isEqualTo(3)
    }

    @Test
    fun `concurrent instances cannot oversubscribe one PostgreSQL bucket`() {
        val firstInstance = PlatformRateLimiter(jdbcTemplate, 2)
        val secondInstance = PlatformRateLimiter(jdbcTemplate, 2)
        val clientId = UUID.randomUUID()
        val executor = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        try {
            val decisions = (0 until 40).map { index ->
                executor.submit<PlatformRateDecision> {
                    start.await(10, TimeUnit.SECONDS)
                    (if (index % 2 == 0) firstInstance else secondInstance).consume(clientId)
                }
            }
            start.countDown()
            val results = decisions.map { it.get(15, TimeUnit.SECONDS) }

            assertThat(results.count(PlatformRateDecision::allowed)).isEqualTo(2)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select request_count from platform_rate_limit_buckets where client_id = ?",
                    Int::class.java,
                    clientId,
                ),
            ).isEqualTo(40)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
