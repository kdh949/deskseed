package dev.deskseed.customerauth.internal

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.system.measureNanoTime

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-auth.request-limit=2",
        "deskseed.customer-auth.request-window=15m",
        "deskseed.customer-auth.fingerprint-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
    ],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerPasswordLoginIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var passwordHasher: CustomerPasswordHasher
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        redis.keys("deskseed:customer-auth:limiter:*").takeIf { it.isNotEmpty() }?.let(redis::delete)
        jdbc.execute(
            """
            truncate table
                outbound_mail_delivery_events, outbound_mail_attempts, outbound_mail_intents,
                customer_one_time_tokens, customer_registration_intent_consents,
                customer_registration_intents, customer_consent_acceptances,
                customer_sessions, customer_accounts, customers, admin_security_audit_events,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `unknown wrong disabled passwordless and incomplete credentials share one response after adaptive work`(
        output: CapturedOutput,
    ) {
        insertPasswordAccount(WRONG_EMAIL)
        insertPasswordAccount(DISABLED_EMAIL, status = "DISABLED")
        insertPasswordlessAccount(PASSWORDLESS_EMAIL)
        insertPendingRegistration(INCOMPLETE_EMAIL)

        val attempts = listOf(
            UNKNOWN_EMAIL to RAW_PASSWORD,
            WRONG_EMAIL to WRONG_PASSWORD,
            DISABLED_EMAIL to RAW_PASSWORD,
            PASSWORDLESS_EMAIL to RAW_PASSWORD,
            INCOMPLETE_EMAIL to RAW_PASSWORD,
        ).map { (email, password) ->
            var result: MvcResult? = null
            val elapsed = measureNanoTime { result = login(email, password).andReturn() }
            requireNotNull(result) to elapsed
        }

        val normalizedProblems = attempts.map { (result, _) ->
            assertThat(result.response.status).isEqualTo(401)
            assertThat(result.response.getHeader("Cache-Control")).isEqualTo("no-store")
            val body = objectMapper.readTree(result.response.contentAsString)
            body.properties().filter { it.key != "requestId" }.associate { it.key to it.value.asString() }
        }
        assertThat(normalizedProblems.distinct()).hasSize(1)
        assertThat(normalizedProblems.distinct().single())
            .containsEntry("type", "/problems/customer-credentials-invalid")
            .containsEntry("title", "고객 인증 정보를 확인할 수 없습니다")
            .containsEntry("status", "401")
        assertThat(attempts.map { it.second }).allSatisfy { elapsed ->
            assertThat(elapsed).isGreaterThan(1_000_000L)
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_PASSWORD_LOGIN_FAILED'",
            Long::class.java,
        )).isEqualTo(5)
        assertThat(auditText()).doesNotContain(
            UNKNOWN_EMAIL,
            WRONG_EMAIL,
            DISABLED_EMAIL,
            PASSWORDLESS_EMAIL,
            INCOMPLETE_EMAIL,
            RAW_PASSWORD,
            WRONG_PASSWORD,
        )
        assertThat(output.all).doesNotContain(
            UNKNOWN_EMAIL,
            WRONG_EMAIL,
            DISABLED_EMAIL,
            PASSWORDLESS_EMAIL,
            INCOMPLETE_EMAIL,
            RAW_PASSWORD,
            WRONG_PASSWORD,
        )
    }

    @Test
    fun `malformed stored Argon2 hash uses dummy work and the generic credential response`() {
        insertPasswordAccount(MALFORMED_EMAIL, passwordHash = MALFORMED_ARGON2_HASH)

        login(MALFORMED_EMAIL, RAW_PASSWORD)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/customer-credentials-invalid"))
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_PASSWORD_LOGIN_FAILED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `password success rotates the current session and returns the bounded credential projection`(
        output: CapturedOutput,
    ) {
        val account = insertPasswordAccount(SUCCESS_EMAIL, companyName = COMPANY_NAME, credentialVersion = 7)
        val oldRawSession = CustomerAuthSecrets.randomBearer()
        insertSession(account.accountId, oldRawSession, credentialVersion = 7)

        val login = login(SUCCESS_EMAIL, RAW_PASSWORD, Cookie(CUSTOMER_COOKIE, oldRawSession))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(jsonPath("$.id").value(account.customerId.toString()))
            .andExpect(jsonPath("$.email").value(SUCCESS_EMAIL))
            .andExpect(jsonPath("$.displayName").value(DISPLAY_NAME))
            .andExpect(jsonPath("$.companyName").value(COMPANY_NAME))
            .andExpect(jsonPath("$.credentialState").value("PASSWORD"))
            .andExpect(jsonPath("$.registrationState").value("COMPLETE"))
            .andExpect(jsonPath("$.availableAuthenticationMethods[0]").value("PASSWORD"))
            .andReturn()
        val newCookie = requireNotNull(login.response.getCookie(CUSTOMER_COOKIE))
        assertSecureCookie(newCookie)
        assertThat(newCookie.value).isNotEqualTo(oldRawSession)

        assertThat(jdbc.queryForObject(
            "select revoked_at is not null from customer_sessions where session_token_digest = ?",
            Boolean::class.java,
            CustomerAuthSecrets.digest(oldRawSession),
        )).isTrue()
        assertThat(jdbc.queryForMap(
            "select authentication_method, credential_version_snapshot from customer_sessions where session_token_digest = ?",
            CustomerAuthSecrets.digest(newCookie.value),
        )).containsEntry("authentication_method", "PASSWORD")
            .containsEntry("credential_version_snapshot", 7L)
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_PASSWORD_LOGIN_SUCCEEDED' and outcome = 'SUCCEEDED'",
            Long::class.java,
        )).isEqualTo(1)

        mockMvc.perform(get("/api/v1/customer/me").cookie(newCookie))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.companyName").value(COMPANY_NAME))
            .andExpect(jsonPath("$.credentialState").value("PASSWORD"))

        jdbc.update("update customer_accounts set credential_version = 8 where id = ?", account.accountId)
        mockMvc.perform(get("/api/v1/customer/me").cookie(newCookie)).andExpect(status().isUnauthorized)

        assertThat(auditText()).doesNotContain(SUCCESS_EMAIL, COMPANY_NAME, RAW_PASSWORD, newCookie.value)
        assertThat(output.all).doesNotContain(SUCCESS_EMAIL, COMPANY_NAME, RAW_PASSWORD, newCookie.value)
    }

    @Test
    fun `exhausted password budget returns generic 429 with retry after`() {
        val email = "rate-limited-password@example.test"
        repeat(2) { login(email, WRONG_PASSWORD).andExpect(status().isUnauthorized) }

        login(email, WRONG_PASSWORD)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-rate-limited"))
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_PASSWORD_LOGIN_RATE_LIMITED'",
            Long::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `required success audit failure rolls session rotation back and returns generic 503`() {
        val account = insertPasswordAccount("audit-failure-password@example.test")
        val oldRawSession = CustomerAuthSecrets.randomBearer()
        insertSession(account.accountId, oldRawSession)
        jdbc.execute(
            """
            create or replace function fail_customer_password_login_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'CUSTOMER_PASSWORD_LOGIN_SUCCEEDED' then
                    raise exception 'injected customer password login audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_customer_password_login_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_customer_password_login_audit()",
        )
        try {
            login("audit-failure-password@example.test", RAW_PASSWORD, Cookie(CUSTOMER_COOKIE, oldRawSession))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
        } finally {
            jdbc.execute("drop trigger if exists fail_customer_password_login_audit on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_customer_password_login_audit()")
        }

        assertThat(jdbc.queryForObject("select count(*) from customer_sessions", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select revoked_at is null from customer_sessions where session_token_digest = ?",
            Boolean::class.java,
            CustomerAuthSecrets.digest(oldRawSession),
        )).isTrue()
    }

    private fun login(email: String, password: String, cookie: Cookie? = null) = mockMvc.perform(
        post("/api/v1/customer/auth/password-sessions")
            .apply { if (cookie != null) cookie(cookie) }
            .with { request -> request.remoteAddr = "192.0.2.91"; request }
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
    )

    private fun insertPasswordAccount(
        email: String,
        status: String = "ACTIVE",
        companyName: String = "합성 회사",
        credentialVersion: Long = 0,
        passwordHash: String? = null,
    ): AccountIds {
        val ids = insertCustomer(email, companyName)
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version, password_hash, password_changed_at, credential_version)
            values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """.trimIndent(),
            ids.accountId,
            ids.customerId,
            email,
            status,
            now,
            now,
            now,
            now,
            passwordHash ?: passwordHasher.encode(RAW_PASSWORD).encoded,
            now,
            credentialVersion,
        )
        return ids
    }

    private fun insertPasswordlessAccount(email: String): AccountIds {
        val ids = insertCustomer(email, null)
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version, password_hash, password_changed_at, credential_version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0, null, null, 0)
            """.trimIndent(),
            ids.accountId,
            ids.customerId,
            email,
            now,
            now,
            now,
            now,
        )
        return ids
    }

    private fun insertCustomer(email: String, companyName: String?): AccountIds {
        val ids = AccountIds(UUID.randomUUID(), UUID.randomUUID())
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customers
                (id, name, email_normalized, email_display, verified_at, created_at, updated_at, company_name)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            ids.customerId,
            DISPLAY_NAME,
            email,
            email,
            now,
            now,
            now,
            companyName,
        )
        return ids
    }

    private fun insertPendingRegistration(email: String) {
        val now = Instant.now()
        jdbc.update(
            """
            insert into customer_registration_intents
                (id, email_normalized, email_display, password_hash, display_name, company_name,
                 continuation_secret_digest, status, request_id, correlation_id,
                 created_at, updated_at, expires_at, consumed_at, cancelled_at, version)
            values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'login-incomplete-request', 'login-incomplete-correlation',
                    ?, ?, ?, null, null, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            email,
            email,
            passwordHasher.encode(RAW_PASSWORD).encoded,
            DISPLAY_NAME,
            COMPANY_NAME,
            "a".repeat(64),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(900)),
        )
    }

    private fun insertSession(accountId: UUID, rawSession: String, credentialVersion: Long = 0) {
        val now = Instant.now()
        jdbc.update(
            """
            insert into customer_sessions
                (id, account_id, session_token_digest, created_at, last_activity_at, expires_at,
                 absolute_expires_at, revoked_at, authentication_method, credential_version_snapshot)
            values (?, ?, ?, ?, ?, ?, ?, null, 'PASSWORD', ?)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            CustomerAuthSecrets.digest(rawSession),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(1800)),
            Timestamp.from(now.plusSeconds(43_200)),
            credentialVersion,
        )
    }

    private fun assertSecureCookie(cookie: Cookie) {
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.secure).isTrue()
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax")
        assertThat(cookie.value).hasSize(43)
    }

    private fun auditText(): String = jdbc.queryForObject(
        "select coalesce(string_agg(metadata_json || coalesce(actor_display_snapshot, ''), ''), '') from admin_security_audit_events",
        String::class.java,
    )!!

    private data class AccountIds(val accountId: UUID, val customerId: UUID)

    companion object {
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"
        private const val RAW_PASSWORD = "synthetic password example only 🔐"
        private const val WRONG_PASSWORD = "synthetic wrong password only 🔐"
        private const val SUCCESS_EMAIL = "password-success@example.test"
        private const val WRONG_EMAIL = "password-wrong@example.test"
        private const val DISABLED_EMAIL = "password-disabled@example.test"
        private const val PASSWORDLESS_EMAIL = "passwordless@example.test"
        private const val INCOMPLETE_EMAIL = "password-incomplete@example.test"
        private const val UNKNOWN_EMAIL = "password-unknown@example.test"
        private const val MALFORMED_EMAIL = "password-malformed@example.test"
        private const val MALFORMED_ARGON2_HASH =
            "${'$'}argon2id${'$'}v=19${'$'}m=19456,t=2,p=1${'$'}not-base64***${'$'}still-not-base64***"
        private const val DISPLAY_NAME = "합성 고객"
        private const val COMPANY_NAME = "합성 회사"

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
