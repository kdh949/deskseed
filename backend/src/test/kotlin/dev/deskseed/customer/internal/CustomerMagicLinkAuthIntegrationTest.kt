package dev.deskseed.customer.internal

import dev.deskseed.customerauth.AuthenticationAttempt
import dev.deskseed.customerauth.AuthenticationAttemptLimiter
import dev.deskseed.customerauth.CustomerAuthenticationPurpose
import dev.deskseed.customerauth.internal.CustomerAuthSecrets
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-auth.magic-link-ttl=15m",
        "deskseed.customer-auth.request-limit=2",
        "deskseed.customer-auth.request-window=15m",
        "deskseed.customer-auth.response-min-duration=20ms",
        "deskseed.customer-auth.session-idle=30m",
        "deskseed.customer-auth.session-absolute=12h",
        "deskseed.customer-auth.fingerprint-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "deskseed.customer-auth.csrf-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
        "deskseed.mail.protected-content.active-key-version=local-v1",
        "deskseed.mail.protected-content.keys.local-v1=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=",
    ],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerMagicLinkAuthIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var oneTimeTokenService: OneTimeTokenService
    @Autowired private lateinit var rateLimiter: AuthenticationAttemptLimiter
    @Autowired private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun clearState() {
        val limiterKeys = redisTemplate.keys("deskseed:customer-auth:limiter:*")
        if (limiterKeys.isNotEmpty()) redisTemplate.delete(limiterKeys)
        jdbcTemplate.execute(
            """
            truncate table
                outbound_mail_delivery_events,
                outbound_mail_attempts,
                outbound_mail_intents,
                customer_sessions,
                customer_one_time_tokens,
                customer_accounts,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
                tickets,
                customers
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `eligible and ineligible requests share 202 while exhausted budget returns generic 429`(output: CapturedOutput) {
        insertUnverifiedCustomer("known@example.com")

        val known = requestMagicLink("known@example.com")
        val unknown = requestMagicLink("unknown@example.com")
        val knownSecond = requestMagicLink("known@example.com")
        val rateLimited = requestMagicLink("known@example.com")

        listOf(known, unknown, knownSecond).forEach { result ->
            assertThat(result.response.status).isEqualTo(202)
            assertThat(result.response.contentAsString).isEqualTo("{\"accepted\":true}")
            assertThat(result.response.getHeader("Cache-Control")).isEqualTo("no-store")
            assertThat(result.response.getHeader("Referrer-Policy")).isEqualTo("no-referrer")
        }
        assertThat(rateLimited.response.status).isEqualTo(429)
        assertThat(rateLimited.response.getHeader("Retry-After")?.toLong()).isBetween(1, 900)
        assertThat(rateLimited.response.contentAsString)
            .contains("/problems/customer-authentication-rate-limited")
            .doesNotContain("known@example.com")
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java))
            .isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents", Long::class.java))
            .isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_MAGIC_LINK_REQUESTED'",
                Long::class.java,
            ),
        ).isEqualTo(3)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_MAGIC_LINK_RATE_LIMITED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("select string_agg(metadata_json, '') from admin_security_audit_events", String::class.java))
            .doesNotContain("known@example.com", "unknown@example.com")
        assertThat(output.all).doesNotContain("known@example.com", "unknown@example.com")
    }

    @Test
    fun `known and unknown requests are padded into the same response timing class`() {
        insertUnverifiedCustomer("timing-known@example.com")
        val elapsed = listOf("timing-known@example.com", "timing-unknown@example.com").map { email ->
            measureNanoTime { requestMagicLink(email) }
        }.map { Duration.ofNanos(it) }

        assertThat(elapsed).allSatisfy { duration ->
            assertThat(duration).isGreaterThanOrEqualTo(Duration.ofMillis(15))
        }
        assertThat(elapsed.max().minus(elapsed.min())).isLessThan(Duration.ofMillis(250))
    }

    @Test
    fun `outbox insert failure rolls database effects back while limiter budget remains committed`() {
        insertUnverifiedCustomer("rollback@example.com")
        jdbcTemplate.execute(
            """
            create or replace function fail_customer_auth_mail_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected customer auth outbox failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_customer_auth_mail_insert before insert on outbound_mail_intents for each row execute function fail_customer_auth_mail_insert()",
        )
        try {
            assertThatThrownBy { requestMagicLink("rollback@example.com") }
                .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_customer_auth_mail_insert on outbound_mail_intents")
            jdbcTemplate.execute("drop function if exists fail_customer_auth_mail_insert()")
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("select count(*) from admin_security_audit_events", Long::class.java)).isZero()
        assertThat(redisTemplate.keys("deskseed:customer-auth:limiter:*")).isNotEmpty
    }

    @Test
    fun `redis script atomically enforces destination budget and stores only fingerprints`() {
        val destination = "atomic-${UUID.randomUUID()}@example.test"
        val executor = Executors.newFixedThreadPool(12)
        val decisions = try {
            executor.invokeAll(
                (1..12).map { index ->
                    Callable {
                        rateLimiter.acquire(
                            AuthenticationAttempt(
                                purpose = CustomerAuthenticationPurpose.MAGIC_LINK_REQUEST,
                                destinationFingerprint = CustomerAuthSecrets.fingerprint(
                                    FINGERPRINT_KEY,
                                    "magic-link-request:destination:$destination",
                                ),
                                requesterNetworkFingerprint = CustomerAuthSecrets.fingerprint(
                                    FINGERPRINT_KEY,
                                    "magic-link-request:network:192.0.2.$index",
                                ),
                            ),
                        )
                    }
                },
            ).map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(decisions.count { it.allowed }).isEqualTo(2)
        assertThat(decisions.filterNot { it.allowed }).allSatisfy { decision ->
            assertThat(decision.retryAfter).isNotNull
            assertThat(decision.retryAfter!!).isGreaterThan(Duration.ZERO)
        }
        val keys = redisTemplate.keys("deskseed:customer-auth:limiter:*")
        assertThat(keys).hasSizeGreaterThanOrEqualTo(4)
        assertThat(keys.joinToString(","))
            .doesNotContain(destination)
            .doesNotContain("192.0.2.")
        assertThat(keys.map { redisTemplate.getExpire(it) }).allSatisfy { ttlSeconds ->
            assertThat(ttlSeconds).isBetween(1, 900)
        }
    }

    @Test
    fun `recipient and header injection inputs are rejected before intent creation`() {
        listOf(
            "not-a-mailbox",
            "victim@example.com\r\nBcc:attacker@example.com",
            "victim@example.com,attacker@example.com",
        ).forEach { email ->
            mockMvc.perform(
                post("/api/v1/customer/auth/magic-link-requests")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(tools.jackson.databind.ObjectMapper().writeValueAsString(mapOf("email" to email))),
            ).andExpect(status().isBadRequest)
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
    }

    @Test
    fun `token is digest only single use and concurrent replay creates one session`(output: CapturedOutput) {
        insertUnverifiedCustomer("race@example.com")
        val rawToken = generateToken("race@example.com")
        assertThat(
            jdbcTemplate.queryForObject(
                "select token_digest from customer_one_time_tokens where email_normalized = ?",
                String::class.java,
                "race@example.com",
            ),
        ).isEqualTo(sha256(rawToken))
        assertThat(databaseText()).doesNotContain(rawToken)

        val executor = Executors.newFixedThreadPool(2)
        val statuses: List<Int> = try {
            executor.invokeAll(
                listOf(
                    Callable<Int> { consume(rawToken).andReturn().response.status },
                    Callable<Int> { consume(rawToken).andReturn().response.status },
                ),
            ).map { it.get(10, TimeUnit.SECONDS) }.sorted()
        } finally {
            executor.shutdownNow()
        }

        assertThat(statuses).containsExactlyElementsOf(listOf(200, 401))
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_sessions", Long::class.java)).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_accounts", Long::class.java)).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_MAGIC_LINK_REPLAYED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(output.all).doesNotContain(rawToken)
    }

    @Test
    fun `expired and malformed tokens use one generic response and create no session`() {
        insertUnverifiedCustomer("expired@example.com")
        val rawToken = "a".repeat(43)
        insertToken(rawToken, "expired@example.com", Instant.now().minusSeconds(60))

        listOf(rawToken, "malformed-token-value").forEach { token ->
            consume(token)
                .andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("/problems/customer-magic-link-invalid"))
        }
        assertThat(jdbcTemplate.queryForObject("select count(*) from customer_sessions", Long::class.java)).isZero()
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_MAGIC_LINK_FAILED'",
                Long::class.java,
            ),
        ).isEqualTo(2)
    }

    @Test
    fun `successful consume rotates session cookie protects logout with csrf and isolates customers`() {
        insertVerifiedCustomer("first@example.com")
        insertVerifiedCustomer("second@example.com")
        val first = consume(generateToken("first@example.com")).andExpect(status().isOk).andReturn()
        val second = consume(generateToken("second@example.com")).andExpect(status().isOk).andReturn()
        val firstCookie = first.response.getCookie(CUSTOMER_COOKIE)!!
        val secondCookie = second.response.getCookie(CUSTOMER_COOKIE)!!

        assertSecureCookie(firstCookie)
        assertThat(firstCookie.value).isNotEqualTo(secondCookie.value)
        currentCustomer(firstCookie).andExpect(status().isOk).andExpect(jsonPath("$.email").value("first@example.com"))
        currentCustomer(secondCookie).andExpect(status().isOk).andExpect(jsonPath("$.email").value("second@example.com"))

        val rotatedCookie = consume(generateToken("first@example.com"), firstCookie)
            .andExpect(status().isOk).andReturn().response.getCookie(CUSTOMER_COOKIE)!!
        assertThat(rotatedCookie.value).isNotEqualTo(firstCookie.value)
        currentCustomer(firstCookie).andExpect(status().isUnauthorized)
        currentCustomer(rotatedCookie).andExpect(status().isOk)

        val csrf = csrf(rotatedCookie).andExpect(status().isOk).andReturn()
            .response.contentAsString.substringAfter("\"token\":\"").substringBefore('"')
        mockMvc.perform(delete("/api/v1/customer/session").cookie(rotatedCookie))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            delete("/api/v1/customer/session")
                .cookie(rotatedCookie)
                .header("X-CSRF-TOKEN", csrf),
        )
            .andExpect(status().isNoContent)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
        currentCustomer(rotatedCookie).andExpect(status().isUnauthorized)
        currentCustomer(secondCookie).andExpect(status().isOk)
    }

    @Test
    fun `verified account creation never rewrites an anonymous ticket requester`() {
        val anonymousCustomer = insertUnverifiedCustomer("claim-safety@example.com")
        val ticketId = insertAnonymousTicket(anonymousCustomer)

        consume(generateToken("claim-safety@example.com")).andExpect(status().isOk)

        val accountCustomer = jdbcTemplate.queryForObject(
            "select customer_id from customer_accounts where email_normalized = ?",
            UUID::class.java,
            "claim-safety@example.com",
        )!!
        assertThat(accountCustomer).isNotEqualTo(anonymousCustomer)
        assertThat(
            jdbcTemplate.queryForObject("select requester_id from tickets where id = ?", UUID::class.java, ticketId),
        ).isEqualTo(anonymousCustomer)
    }

    private fun requestMagicLink(email: String) = mockMvc.perform(
        post("/api/v1/customer/auth/magic-link-requests")
            .with { request -> request.remoteAddr = "192.0.2.10"; request }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email"}"""),
    ).andReturn()

    private fun generateToken(email: String): String = transactionTemplate.execute {
        oneTimeTokenService.generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue
    }

    private fun consume(token: String, cookie: Cookie? = null) = mockMvc.perform(
        post("/api/v1/customer/auth/magic-link-sessions")
            .apply { if (cookie != null) cookie(cookie) }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"$token"}"""),
    )

    private fun currentCustomer(cookie: Cookie) = mockMvc.perform(get("/api/v1/customer/me").cookie(cookie))

    private fun csrf(cookie: Cookie) = mockMvc.perform(get("/api/v1/customer/csrf").cookie(cookie))

    private fun insertUnverifiedCustomer(email: String): UUID = insertCustomer(email, null)

    private fun insertVerifiedCustomer(email: String): UUID = insertCustomer(email, Instant.now())

    private fun insertCustomer(email: String, verifiedAt: Instant?): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now()
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Customer', ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            email,
            email,
            verifiedAt?.let(Timestamp::from),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return id
    }

    private fun insertAnonymousTicket(customerId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbcTemplate.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority,
                 group_id, assignee_id, channel, version, created_at, updated_at, solved_at)
            values (?, nextval('ticket_number_seq'), ?, 'CUSTOMER_REQUEST', 'Historical anonymous request',
                    'NEW', 'NORMAL', null, null, 'WEB', 0, ?, ?, null)
            """.trimIndent(),
            id,
            customerId,
            now,
            now,
        )
        return id
    }

    private fun insertToken(rawToken: String, email: String, expiresAt: Instant) {
        val now = Instant.now().minusSeconds(120)
        jdbcTemplate.update(
            """
            insert into customer_one_time_tokens
                (id, token_digest, purpose, email_normalized, email_display, request_id, correlation_id,
                 created_at, expires_at, consumed_at)
            values (?, ?, 'PASSWORDLESS_LOGIN', ?, ?, 'expired-request', 'expired-correlation', ?, ?, null)
            """.trimIndent(),
            UUID.randomUUID(),
            sha256(rawToken),
            email,
            email,
            Timestamp.from(now),
            Timestamp.from(expiresAt),
        )
    }

    private fun assertSecureCookie(cookie: Cookie) {
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.secure).isTrue()
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax")
        assertThat(cookie.value).hasSize(43)
        assertThat(databaseText()).doesNotContain(cookie.value)
    }

    private fun databaseText(): String = jdbcTemplate.queryForObject(
        """
        select coalesce(string_agg(value, ''), '')
        from (
            select token_digest as value from customer_one_time_tokens
            union all
            select session_token_digest from customer_sessions
            union all
            select metadata_json from admin_security_audit_events
        ) values_to_scan
        """.trimIndent(),
        String::class.java,
    )!!

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    companion object {
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"
        private const val FINGERPRINT_KEY = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="

        @Container
        @JvmStatic
        val redis = GenericContainer(DockerImageName.parse("redis:8.2.9-alpine"))
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redis::getHost)
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }
        }
    }
}
