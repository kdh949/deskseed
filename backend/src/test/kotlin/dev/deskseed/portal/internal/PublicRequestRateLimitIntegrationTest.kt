package dev.deskseed.portal.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(
    properties = [
        "deskseed.portal.public-request-rate-limit.enabled=true",
        "deskseed.portal.public-request-rate-limit.window=1m",
        "deskseed.portal.public-request-rate-limit.destination-limit=2",
        "deskseed.portal.public-request-rate-limit.client-limit=2",
        "deskseed.portal.public-request-rate-limit.global-limit=3",
        "deskseed.portal.public-request-rate-limit.fingerprint-key=CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
        "deskseed.portal.public-request-rate-limit.trusted-proxy-cidrs=192.0.2.0/24",
        "deskseed.portal.public-request-rate-limit.max-forwarded-hops=2",
        "deskseed.portal.public-request-rate-limit.cleanup-batch-size=2",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PublicRequestRateLimitIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var rateLimiter: PublicRequestRateLimiter
    @Autowired private lateinit var retentionJob: PublicRequestRateLimitRetentionJob
    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @BeforeEach
    fun clearRateLimitBuckets() {
        jdbcTemplate.execute("truncate table public_request_rate_limit_buckets")
    }

    @AfterEach
    fun clearRateLimitBucketsAfterTest() {
        jdbcTemplate.execute("truncate table public_request_rate_limit_buckets")
    }

    @Test
    fun `destination and client buckets reject atomically with Retry-After and persist no raw identity`() {
        val destination = "destination-${UUID.randomUUID()}@example.com"
        submit(destination, "198.51.100.10").andExpect(status().isCreated)
        submit(destination, "198.51.100.11").andExpect(status().isCreated)
        val destinationLimited = submit(destination, "198.51.100.12")
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("/problems/request-rate-limit-exceeded"))
            .andReturn()
        assertThat(destinationLimited.response.getHeader("Retry-After")?.toLong()).isBetween(1, 60)

        val destinationFingerprints = jdbcTemplate.queryForList(
            "select bucket_fingerprint from public_request_rate_limit_buckets",
            String::class.java,
        ).filterNotNull().joinToString(",")
        assertThat(destinationFingerprints).doesNotContain(destination)

        jdbcTemplate.execute("truncate table public_request_rate_limit_buckets")
        val client = "198.51.100.20"
        submit("client-a-${UUID.randomUUID()}@example.com", client).andExpect(status().isCreated)
        submit("client-b-${UUID.randomUUID()}@example.com", client).andExpect(status().isCreated)
        submit("client-c-${UUID.randomUUID()}@example.com", client)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))

        val fingerprints = jdbcTemplate.queryForList(
            "select bucket_fingerprint from public_request_rate_limit_buckets",
            String::class.java,
        ).filterNotNull().joinToString(",")
        assertThat(fingerprints).doesNotContain(destination).doesNotContain(client)
        assertThat(
            jdbcTemplate.queryForList(
                """
                select column_name from information_schema.columns
                where table_schema = 'public' and table_name = 'public_request_rate_limit_buckets'
                """.trimIndent(),
                String::class.java,
            ),
        ).doesNotContain("destination", "email", "client_address", "remote_address", "forwarded_for")
    }

    @Test
    fun `global bucket applies across independently fingerprinted destinations and clients`() {
        repeat(3) { index ->
            submit("global-$index-${UUID.randomUUID()}@example.com", "198.51.100.${30 + index}")
                .andExpect(status().isCreated)
        }
        submit("global-over-${UUID.randomUUID()}@example.com", "198.51.100.40")
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
    }

    @Test
    fun `untrusted peer cannot select a different client bucket with forwarded headers`() {
        val peer = "198.51.100.90"
        repeat(2) { index ->
            submit(
                email = "spoof-$index-${UUID.randomUUID()}@example.com",
                remoteAddress = peer,
                forwardedFor = "203.0.113.${10 + index}",
            ).andExpect(status().isCreated)
        }
        submit(
            email = "spoof-over-${UUID.randomUUID()}@example.com",
            remoteAddress = peer,
            forwardedFor = "203.0.113.99",
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.type").value("/problems/request-rate-limit-exceeded"))
    }

    @Test
    fun `trusted proxy derives the first untrusted forwarded client from a bounded chain`() {
        val forwardedClient = "203.0.113.41, 192.0.2.20"
        repeat(2) { index ->
            submit(
                email = "trusted-$index-${UUID.randomUUID()}@example.com",
                remoteAddress = "192.0.2.${10 + index}",
                forwardedFor = forwardedClient,
            ).andExpect(status().isCreated)
        }
        submit(
            email = "trusted-over-${UUID.randomUUID()}@example.com",
            remoteAddress = "192.0.2.12",
            forwardedFor = forwardedClient,
        ).andExpect(status().isTooManyRequests)
    }

    @Test
    fun `malformed or oversized forwarding chains from trusted proxy fail closed before bucket or ticket creation`() {
        val malformedEmail = "malformed-${UUID.randomUUID()}@example.com"
        submit(malformedEmail, "192.0.2.10", "not-an-ip")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/request-network-invalid"))
        submit("oversized-${UUID.randomUUID()}@example.com", "192.0.2.10", "203.0.113.1, 203.0.113.2, 203.0.113.3")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/request-network-invalid"))

        assertThat(
            jdbcTemplate.queryForObject("select count(*) from public_request_rate_limit_buckets", Long::class.java),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from customers where email_normalized = ?", Long::class.java, malformedEmail),
        ).isZero()
    }

    @Test
    fun `concurrent durable bucket upserts admit exactly the configured client limit`() {
        val callers = 8
        val barrier = CyclicBarrier(callers)
        val executor = Executors.newFixedThreadPool(callers)
        val admitted = AtomicInteger()
        val rejected = AtomicInteger()
        try {
            val futures = (1..callers).map {
                executor.submit {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        rateLimiter.consume("concurrent-${UUID.randomUUID()}@example.com", "198.51.100.200")
                        admitted.incrementAndGet()
                    } catch (_: PublicRequestRateLimitExceededException) {
                        rejected.incrementAndGet()
                    }
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(admitted.get()).isEqualTo(2)
        assertThat(rejected.get()).isEqualTo(callers - 2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select request_count from public_request_rate_limit_buckets where bucket_type = 'CLIENT'",
                Int::class.java,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `separate limiter instances cannot split one database bucket at a local clock boundary`() {
        val destination = "clock-skew-${UUID.randomUUID()}@example.com"
        val clientAddress = "198.51.100.221"
        val properties = PublicRequestRateLimitProperties(
            window = java.time.Duration.ofMinutes(1),
            destinationLimit = 1,
            clientLimit = 1,
            globalLimit = 1,
            fingerprintKey = "CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=",
        )
        // Nodes may have local clocks on opposite sides of a boundary. The limiter deliberately has no Clock
        // dependency: the timestamp that selects this shared bucket comes from PostgreSQL.
        val beforeBoundary = PublicRequestRateLimiter(jdbcTemplate, properties)
        val afterBoundary = PublicRequestRateLimiter(jdbcTemplate, properties)

        transactionTemplate.executeWithoutResult { beforeBoundary.consume(destination, clientAddress) }

        org.assertj.core.api.Assertions.assertThatThrownBy {
            transactionTemplate.executeWithoutResult { afterBoundary.consume(destination, clientAddress) }
        }.isInstanceOf(PublicRequestRateLimitExceededException::class.java)

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(distinct window_started_at) from public_request_rate_limit_buckets",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `expiry cleanup deletes bounded expired buckets`() {
        val now = Instant.parse("2026-08-15T00:00:00Z")
        insertExpiredBucket("a".repeat(64), now)
        insertExpiredBucket("b".repeat(64), now)
        jdbcTemplate.update(
            """
            insert into public_request_rate_limit_buckets
                (bucket_type, bucket_fingerprint, window_started_at, request_count, expires_at, updated_at)
            values ('GLOBAL', ?, ?, 1, ?, ?)
            """.trimIndent(),
            "c".repeat(64),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(60)),
            Timestamp.from(now),
        )

        assertThat(retentionJob.purgeExpired(now)).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from public_request_rate_limit_buckets", Long::class.java),
        ).isEqualTo(1)
    }

    @Test
    fun `limiter persistence failure returns unavailable without creating a customer or ticket`() {
        val email = "unavailable-${UUID.randomUUID()}@example.com"
        val functionName = "fail_public_request_rate_limit_insert"
        val triggerName = "${functionName}_trigger"
        jdbcTemplate.execute(
            """
            create function $functionName() returns trigger as $$
            begin
                raise exception 'injected rate-limit failure';
            end;
            $$ language plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger $triggerName before insert on public_request_rate_limit_buckets " +
                "for each row execute function $functionName()",
        )
        try {
            submit(email, "198.51.100.250")
                .andExpect(status().isServiceUnavailable)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("/problems/request-rate-limit-unavailable"))
        } finally {
            jdbcTemplate.execute("drop trigger if exists $triggerName on public_request_rate_limit_buckets")
            jdbcTemplate.execute("drop function if exists $functionName()")
        }
        assertThat(
            jdbcTemplate.queryForObject("select count(*) from customers where email_normalized = ?", Long::class.java, email),
        ).isZero()
    }

    private fun submit(
        email: String,
        remoteAddress: String,
        forwardedFor: String? = null,
    ) = mockMvc.perform(
        post("/api/v1/requests")
            .with { request ->
                request.remoteAddr = remoteAddress
                request
            }
            .apply { if (forwardedFor != null) header("X-Forwarded-For", forwardedFor) }
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "name": "문의 고객",
                  "email": "$email",
                  "subject": "공개 문의 제한 검증",
                  "message": "공개 문의 제한이 요청 생성 전에 동작하는지 확인합니다."
                }
                """.trimIndent(),
            ),
    )

    private fun insertExpiredBucket(fingerprint: String, now: Instant) {
        jdbcTemplate.update(
            """
            insert into public_request_rate_limit_buckets
                (bucket_type, bucket_fingerprint, window_started_at, request_count, expires_at, updated_at)
            values ('CLIENT', ?, ?, 1, ?, ?)
            """.trimIndent(),
            fingerprint,
            Timestamp.from(now.minusSeconds(120)),
            Timestamp.from(now.minusSeconds(60)),
            Timestamp.from(now.minusSeconds(120)),
        )
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
