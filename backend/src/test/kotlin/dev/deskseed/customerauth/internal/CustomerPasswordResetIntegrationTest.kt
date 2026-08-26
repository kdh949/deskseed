package dev.deskseed.customerauth.internal

import dev.deskseed.outboundmail.internal.ProtectedMailContent
import dev.deskseed.outboundmail.internal.ProtectedMailContentCipher
import dev.deskseed.outboundmail.internal.ProtectedMailContentProperties
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-auth.password-reset-ttl=30m",
        "deskseed.customer-auth.password-reset-url=https://deskseed.example/customer/password/reset",
        "deskseed.customer-auth.request-limit=2",
        "deskseed.customer-auth.request-window=15m",
        "deskseed.customer-auth.response-min-duration=20ms",
        "deskseed.customer-auth.fingerprint-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "deskseed.mail.protected-content.active-key-version=local-v1",
        "deskseed.mail.protected-content.keys.local-v1=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=",
    ],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerPasswordResetIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate
    @Autowired private lateinit var passwordHasher: CustomerPasswordHasher

    @BeforeEach
    fun setUp() {
        redis.keys("deskseed:customer-auth:limiter:*").takeIf { it.isNotEmpty() }?.let(redis::delete)
        clearState()
    }

    @Test
    fun `only active password account gets a protected reset proof while every identity receives the same 202`(
        output: CapturedOutput,
    ) {
        val eligibleAccount = insertAccount(ELIGIBLE_EMAIL, password = RAW_PASSWORD)
        insertAccount(PASSWORDLESS_EMAIL, password = null)
        insertAccount(DISABLED_EMAIL, password = RAW_PASSWORD, status = "DISABLED")

        val attempts = listOf(ELIGIBLE_EMAIL, UNKNOWN_EMAIL, PASSWORDLESS_EMAIL, DISABLED_EMAIL).map { email ->
            var result: MvcResult? = null
            val elapsed = measureNanoTime { result = requestReset(email).andReturn() }
            requireNotNull(result) to elapsed
        }
        attempts.forEach { (result, elapsed) ->
            assertThat(result.response.status).isEqualTo(202)
            assertThat(result.response.contentAsString).isEqualTo("{\"accepted\":true}")
            assertThat(result.response.getHeader("Cache-Control")).isEqualTo("no-store")
            assertThat(result.response.getHeader("Referrer-Policy")).isEqualTo("no-referrer")
            assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(20).toNanos())
        }

        val tokenRow = jdbc.queryForMap(
            """
            select id, token_digest, purpose, account_id, email_normalized, consumed_at,
                   extract(epoch from (expires_at - created_at))::bigint as ttl_seconds
              from customer_one_time_tokens
            """.trimIndent(),
        )
        assertThat(tokenRow)
            .containsEntry("purpose", "PASSWORD_RESET")
            .containsEntry("account_id", eligibleAccount)
            .containsEntry("email_normalized", ELIGIBLE_EMAIL)
            .containsEntry("ttl_seconds", 1800L)
        assertThat(tokenRow["consumed_at"]).isNull()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_PASSWORD_RESET_REQUESTED'",
            Long::class.java,
        )).isEqualTo(4)

        val rawToken = resetTokenFromProtectedMail()
        assertThat(tokenRow["token_digest"]).isEqualTo(sha256(rawToken))
        assertThat(databaseText()).doesNotContain(rawToken)
        assertThat(auditText()).doesNotContain(
            ELIGIBLE_EMAIL,
            UNKNOWN_EMAIL,
            PASSWORDLESS_EMAIL,
            DISABLED_EMAIL,
        )
        assertThat(output.all).doesNotContain(
            rawToken,
            ELIGIBLE_EMAIL,
            UNKNOWN_EMAIL,
            PASSWORDLESS_EMAIL,
            DISABLED_EMAIL,
        )
    }

    @Test
    fun `reset request rate limit returns generic 429 with retry after`() {
        val email = "reset-rate-limited@example.test"
        repeat(2) { requestReset(email).andExpect(status().isAccepted) }

        requestReset(email)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-rate-limited"))
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_PASSWORD_RESET_REQUESTED' and outcome = 'DENIED'",
            Long::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `reset outbox failure rolls token and audit back while limiter allowance stays committed`() {
        insertAccount("reset-outbox-failure@example.test", password = RAW_PASSWORD)
        jdbc.execute(
            """
            create or replace function fail_password_reset_mail_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected password reset outbox failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_password_reset_mail_insert before insert on outbound_mail_intents " +
                "for each row execute function fail_password_reset_mail_insert()",
        )
        try {
            requestReset("reset-outbox-failure@example.test")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
        } finally {
            jdbc.execute("drop trigger if exists fail_password_reset_mail_insert on outbound_mail_intents")
            jdbc.execute("drop function if exists fail_password_reset_mail_insert()")
        }

        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events", Long::class.java)).isZero()
        assertThat(redis.keys("deskseed:customer-auth:limiter:*")).isNotEmpty
    }

    @Test
    fun `required reset audit failure rolls token and mail back`() {
        insertAccount("reset-audit-failure@example.test", password = RAW_PASSWORD)
        jdbc.execute(
            """
            create or replace function fail_password_reset_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected password reset audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_password_reset_audit_insert before insert on admin_security_audit_events " +
                "for each row execute function fail_password_reset_audit_insert()",
        )
        try {
            requestReset("reset-audit-failure@example.test")
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
        } finally {
            jdbc.execute("drop trigger if exists fail_password_reset_audit_insert on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_password_reset_audit_insert()")
        }

        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events", Long::class.java)).isZero()
        assertThat(redis.keys("deskseed:customer-auth:limiter:*")).isNotEmpty
    }

    @Test
    fun `valid reset changes password once revokes every old session and expires the current cookie`(
        output: CapturedOutput,
    ) {
        val accountId = insertAccount(RESET_EMAIL, password = RAW_PASSWORD, credentialVersion = 4)
        val customerId = jdbc.queryForObject(
            "select customer_id from customer_accounts where id = ?",
            UUID::class.java,
            accountId,
        )!!
        val rawToken = "synthetic-reset-proof-success-0000000000000001"
        val firstSession = "synthetic-reset-session-first-000000000000001"
        val secondSession = "synthetic-reset-session-second-00000000000002"
        insertOneTimeToken(rawToken, "PASSWORD_RESET", accountId, RESET_EMAIL)
        insertSession(accountId, firstSession, credentialVersion = 4, authenticationMethod = "PASSWORD")
        insertSession(accountId, secondSession, credentialVersion = 4, authenticationMethod = "MAGIC_LINK")

        val reset = consumeReset(rawToken, NEW_PASSWORD, Cookie(CUSTOMER_COOKIE, firstSession))
            .andExpect(status().isNoContent)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andReturn()
        val expired = requireNotNull(reset.response.getCookie(CUSTOMER_COOKIE))
        assertThat(expired.value).isEmpty()
        assertThat(expired.maxAge).isZero()
        assertThat(expired.isHttpOnly).isTrue()
        assertThat(expired.secure).isTrue()

        val account = jdbc.queryForMap(
            "select password_hash, credential_version, version from customer_accounts where id = ?",
            accountId,
        )
        assertThat(passwordHasher.matches(NEW_PASSWORD, CustomerPasswordHash.fromEncoded(account["password_hash"] as String)))
            .isTrue()
        assertThat(account).containsEntry("credential_version", 5L).containsEntry("version", 1L)
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_sessions where account_id = ? and revoked_at is not null",
            Long::class.java,
            accountId,
        )).isEqualTo(2)
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions", Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject(
            "select consumed_at is not null from customer_one_time_tokens where token_digest = ?",
            Boolean::class.java,
            sha256(rawToken),
        )).isTrue()
        assertThat(jdbc.queryForMap(
            """
            select actor_type, actor_id, target_type, target_id, outcome
              from admin_security_audit_events
             where event_type = 'CUSTOMER_PASSWORD_RESET_COMPLETED'
            """.trimIndent(),
        )).containsEntry("actor_type", "CUSTOMER")
            .containsEntry("actor_id", customerId)
            .containsEntry("target_type", "CUSTOMER_ACCOUNT")
            .containsEntry("target_id", accountId)
            .containsEntry("outcome", "SUCCEEDED")

        mockMvc.perform(get("/api/v1/customer/me").cookie(Cookie(CUSTOMER_COOKIE, firstSession)))
            .andExpect(status().isUnauthorized)
        login(RESET_EMAIL, RAW_PASSWORD).andExpect(status().isUnauthorized)
        login(RESET_EMAIL, NEW_PASSWORD).andExpect(status().isOk)

        consumeReset(rawToken, SECOND_NEW_PASSWORD)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/customer-one-time-proof-invalid"))
        assertThat(jdbc.queryForObject(
            "select credential_version from customer_accounts where id = ?",
            Long::class.java,
            accountId,
        )).isEqualTo(5L)
        assertThat(auditText()).doesNotContain(
            RESET_EMAIL,
            RAW_PASSWORD,
            NEW_PASSWORD,
            SECOND_NEW_PASSWORD,
            rawToken,
            firstSession,
            secondSession,
        )
        assertThat(output.all).doesNotContain(
            RESET_EMAIL,
            RAW_PASSWORD,
            NEW_PASSWORD,
            SECOND_NEW_PASSWORD,
            rawToken,
            firstSession,
            secondSession,
        )
    }

    @Test
    fun `unknown wrong purpose and expired reset proofs share one 401 without credential mutation`() {
        val accountId = insertAccount("invalid-reset-proof@example.test", password = RAW_PASSWORD)
        val oldHash = passwordHash(accountId)
        val expired = "synthetic-reset-proof-expired-00000000000000001"
        val wrongPurpose = "synthetic-reset-proof-wrong-purpose-000000000001"
        val unknown = "synthetic-reset-proof-unknown-00000000000000001"
        insertOneTimeToken(
            expired,
            "PASSWORD_RESET",
            accountId,
            "invalid-reset-proof@example.test",
            expiresAt = Instant.now().minusSeconds(1),
        )
        insertOneTimeToken(wrongPurpose, "PASSWORDLESS_LOGIN", null, "invalid-reset-proof@example.test")

        listOf(expired, wrongPurpose, unknown).forEach { proof ->
            consumeReset(proof, NEW_PASSWORD)
                .andExpect(status().isUnauthorized)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.type").value("/problems/customer-one-time-proof-invalid"))
        }

        assertThat(passwordHash(accountId)).isEqualTo(oldHash)
        assertThat(jdbc.queryForObject(
            "select credential_version from customer_accounts where id = ?",
            Long::class.java,
            accountId,
        )).isZero()
        assertThat(jdbc.queryForObject(
            """
            select count(*) from admin_security_audit_events
             where event_type = 'CUSTOMER_PASSWORD_RESET_COMPLETED' and outcome = 'DENIED'
            """.trimIndent(),
            Long::class.java,
        )).isEqualTo(3)
    }

    @Test
    fun `concurrent reset proofs for one account produce one password change without deadlock`() {
        val accountId = insertAccount("concurrent-reset@example.test", password = RAW_PASSWORD)
        val firstProof = "synthetic-concurrent-reset-proof-first-00000000001"
        val secondProof = "synthetic-concurrent-reset-proof-second-0000000002"
        val oldSession = "synthetic-concurrent-reset-session-00000000000001"
        insertOneTimeToken(firstProof, "PASSWORD_RESET", accountId, "concurrent-reset@example.test")
        insertOneTimeToken(secondProof, "PASSWORD_RESET", accountId, "concurrent-reset@example.test")
        insertSession(accountId, oldSession)

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(firstProof to NEW_PASSWORD, secondProof to SECOND_NEW_PASSWORD).map { (proof, password) ->
                executor.submit<Int> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    consumeReset(proof, password).andReturn().response.status
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(attempts.map { it.get(30, TimeUnit.SECONDS) }.sorted()).containsExactly(204, 401)
        } finally {
            executor.shutdownNow()
        }

        val currentHash = CustomerPasswordHash.fromEncoded(passwordHash(accountId))
        assertThat(
            passwordHasher.matches(NEW_PASSWORD, currentHash) xor
                passwordHasher.matches(SECOND_NEW_PASSWORD, currentHash),
        ).isTrue()
        assertThat(jdbc.queryForObject(
            "select credential_version from customer_accounts where id = ?",
            Long::class.java,
            accountId,
        )).isEqualTo(1L)
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_sessions where account_id = ? and revoked_at is not null",
            Long::class.java,
            accountId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_one_time_tokens where account_id = ? and consumed_at is not null",
            Long::class.java,
            accountId,
        )).isEqualTo(2)
        assertThat(jdbc.queryForObject(
            """
            select count(*) from admin_security_audit_events
             where event_type = 'CUSTOMER_PASSWORD_RESET_COMPLETED' and outcome = 'SUCCEEDED'
            """.trimIndent(),
            Long::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `required reset completion audit failure rolls credential token and sessions back`() {
        val accountId = insertAccount("reset-completion-audit-failure@example.test", password = RAW_PASSWORD)
        val oldHash = passwordHash(accountId)
        val rawToken = "synthetic-reset-completion-audit-proof-0000000001"
        val oldSession = "synthetic-reset-completion-audit-session-00000001"
        insertOneTimeToken(rawToken, "PASSWORD_RESET", accountId, "reset-completion-audit-failure@example.test")
        insertSession(accountId, oldSession)
        jdbc.execute(
            """
            create or replace function fail_password_reset_completion_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'CUSTOMER_PASSWORD_RESET_COMPLETED' then
                    raise exception 'injected password reset completion audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_password_reset_completion_audit_insert before insert on admin_security_audit_events " +
                "for each row execute function fail_password_reset_completion_audit_insert()",
        )
        try {
            consumeReset(rawToken, NEW_PASSWORD)
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
        } finally {
            jdbc.execute(
                "drop trigger if exists fail_password_reset_completion_audit_insert on admin_security_audit_events",
            )
            jdbc.execute("drop function if exists fail_password_reset_completion_audit_insert()")
        }

        assertThat(passwordHash(accountId)).isEqualTo(oldHash)
        assertThat(jdbc.queryForObject(
            "select credential_version from customer_accounts where id = ?",
            Long::class.java,
            accountId,
        )).isZero()
        assertThat(jdbc.queryForObject(
            "select consumed_at is null from customer_one_time_tokens where token_digest = ?",
            Boolean::class.java,
            sha256(rawToken),
        )).isTrue()
        assertThat(jdbc.queryForObject(
            "select revoked_at is null from customer_sessions where session_token_digest = ?",
            Boolean::class.java,
            sha256(oldSession),
        )).isTrue()

        consumeReset(rawToken, NEW_PASSWORD).andExpect(status().isNoContent)
    }

    @Test
    fun `reset consume limit returns generic 429 with retry after`() {
        val rawToken = "synthetic-reset-rate-limit-proof-0000000000000001"
        repeat(2) {
            consumeReset(rawToken, NEW_PASSWORD).andExpect(status().isUnauthorized)
        }

        consumeReset(rawToken, NEW_PASSWORD)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-rate-limited"))
    }

    private fun requestReset(email: String) = mockMvc.perform(
        post("/api/v1/customer/auth/password-reset-requests")
            .with { request -> request.remoteAddr = "192.0.2.101"; request }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email"}"""),
    )

    private fun consumeReset(rawToken: String, newPassword: String, cookie: Cookie? = null) = mockMvc.perform(
        post("/api/v1/customer/auth/password-resets")
            .with { request -> request.remoteAddr = "192.0.2.102"; request }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"$rawToken","newPassword":"$newPassword"}""")
            .let { request -> if (cookie == null) request else request.cookie(cookie) },
    )

    private fun login(email: String, password: String) = mockMvc.perform(
        post("/api/v1/customer/auth/password-sessions")
            .with { request -> request.remoteAddr = "192.0.2.103"; request }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"$password"}"""),
    )

    private fun insertAccount(
        email: String,
        password: String?,
        status: String = "ACTIVE",
        credentialVersion: Long = 0,
    ): UUID {
        val customerId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customers
                (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Reset Customer', ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            email,
            email,
            now,
            now,
            now,
        )
        val hash = password?.let(passwordHasher::encode)
        jdbc.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version, password_hash, password_changed_at, credential_version)
            values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
            """.trimIndent(),
            accountId,
            customerId,
            email,
            status,
            now,
            now,
            now,
            now,
            hash?.encoded,
            hash?.let { now },
            credentialVersion,
        )
        return accountId
    }

    private fun insertOneTimeToken(
        rawToken: String,
        purpose: String,
        accountId: UUID?,
        email: String,
        expiresAt: Instant = Instant.now().plusSeconds(1_800),
    ) {
        val createdAt = Instant.now().minusSeconds(2)
        jdbc.update(
            """
            insert into customer_one_time_tokens
                (id, token_digest, purpose, registration_intent_id, account_id,
                 email_normalized, email_display, request_id, correlation_id,
                 created_at, expires_at, consumed_at)
            values (?, ?, ?, null, ?, ?, ?, 'reset-test-request', 'reset-test-correlation', ?, ?, null)
            """.trimIndent(),
            UUID.randomUUID(),
            sha256(rawToken),
            purpose,
            accountId,
            email,
            email,
            Timestamp.from(createdAt),
            Timestamp.from(expiresAt),
        )
    }

    private fun insertSession(
        accountId: UUID,
        rawSession: String,
        credentialVersion: Long = 0,
        authenticationMethod: String = "PASSWORD",
    ) {
        val now = Instant.now()
        jdbc.update(
            """
            insert into customer_sessions
                (id, account_id, session_token_digest, created_at, last_activity_at,
                 expires_at, absolute_expires_at, revoked_at, authentication_method,
                 credential_version_snapshot)
            values (?, ?, ?, ?, ?, ?, ?, null, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            sha256(rawSession),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(1_800)),
            Timestamp.from(now.plusSeconds(3_600)),
            authenticationMethod,
            credentialVersion,
        )
    }

    private fun passwordHash(accountId: UUID): String = jdbc.queryForObject(
        "select password_hash from customer_accounts where id = ?",
        String::class.java,
        accountId,
    )!!

    private fun resetTokenFromProtectedMail(): String {
        val stored = jdbc.queryForMap(
            """
            select id, protected_body_ciphertext, protected_body_nonce, protected_body_key_version
              from outbound_mail_intents
             where template_key = 'CUSTOMER_PASSWORD_RESET'
            """.trimIndent(),
        )
        val intentId = stored["id"] as UUID
        val plaintext = ProtectedMailContentCipher(
            ProtectedMailContentProperties(
                activeKeyVersion = "local-v1",
                keys = mapOf("local-v1" to PROTECTED_MAIL_KEY),
            ),
        ).decrypt(
            ProtectedMailContent(
                ciphertext = stored["protected_body_ciphertext"] as ByteArray,
                nonce = stored["protected_body_nonce"] as ByteArray,
                keyVersion = stored["protected_body_key_version"] as String,
            ),
            intentId,
        )
        return plaintext.substringAfter("#token=").lineSequence().first().trim()
    }

    private fun databaseText(): String = jdbc.queryForObject(
        """
        select coalesce(string_agg(value, ''), '')
          from (
            select token_digest as value from customer_one_time_tokens
            union all
            select text_body from outbound_mail_intents
            union all
            select metadata_json from admin_security_audit_events
          ) reset_material
        """.trimIndent(),
        String::class.java,
    )!!

    private fun auditText(): String = jdbc.queryForObject(
        "select coalesce(string_agg(metadata_json, ''), '') from admin_security_audit_events",
        String::class.java,
    )!!

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun clearState() {
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

    companion object {
        private const val ELIGIBLE_EMAIL = "reset-eligible@example.test"
        private const val UNKNOWN_EMAIL = "reset-unknown@example.test"
        private const val PASSWORDLESS_EMAIL = "reset-passwordless@example.test"
        private const val DISABLED_EMAIL = "reset-disabled@example.test"
        private const val RAW_PASSWORD = "synthetic current password 🔐"
        private const val NEW_PASSWORD = "synthetic replacement password one 🔐"
        private const val SECOND_NEW_PASSWORD = "synthetic replacement password two 🔐"
        private const val RESET_EMAIL = "reset-success@example.test"
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"
        private const val PROTECTED_MAIL_KEY = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="

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
