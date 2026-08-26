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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-auth.request-limit=2",
        "deskseed.customer-auth.request-window=15m",
        "deskseed.customer-auth.fingerprint-key=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
        "deskseed.customer-auth.csrf-key=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",
    ],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@Testcontainers
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerPasswordlessRegistrationCompletionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var passwordHasher: CustomerPasswordHasher
    @Autowired private lateinit var properties: CustomerAuthProperties

    @BeforeEach
    fun clearState() {
        jdbc.execute(
            """
            truncate table
                customer_consent_acceptances,
                customer_consent_policy_versions,
                customer_consent_policies,
                customer_sessions,
                customer_one_time_tokens,
                customer_accounts,
                admin_security_audit_events,
                ticket_comments,
                tickets,
                customers,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `completion atomically sets password profile consents and rotates every old session without claiming tickets`(
        output: CapturedOutput,
    ) {
        insertCurrentRegistrationPolicy()
        val email = "completion-success@example.test"
        val anonymousCustomerId = insertCustomer(email, verified = false, name = "Anonymous requester")
        val ticketId = insertAnonymousTicket(anonymousCustomerId)
        val account = insertAccount(email)
        val olderSession = CustomerAuthSecrets.randomBearer()
        insertSession(account.accountId, olderSession)

        val completed = complete(account)
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.displayName").value("김민아"))
            .andExpect(jsonPath("$.companyName").value("가온상사"))
            .andExpect(jsonPath("$.credentialState").value("PASSWORD"))
            .andExpect(jsonPath("$.registrationState").value("COMPLETE"))
            .andExpect(jsonPath("$.availableAuthenticationMethods[0]").value("PASSWORD"))
            .andReturn()
        val newCookie = requireNotNull(completed.response.getCookie(CUSTOMER_COOKIE))
        assertSecureCookie(newCookie)
        assertThat(newCookie.value).isNotEqualTo(account.rawSession)

        val accountState = jdbc.queryForMap(
            "select password_hash, password_changed_at, credential_version, version from customer_accounts where id = ?",
            account.accountId,
        )
        assertThat(passwordHasher.matches(RAW_PASSWORD, CustomerPasswordHash.fromEncoded(accountState["password_hash"] as String)))
            .isTrue()
        assertThat(accountState["password_changed_at"]).isNotNull
        assertThat(accountState["credential_version"]).isEqualTo(1L)
        assertThat(accountState["version"]).isEqualTo(1L)
        assertThat(jdbc.queryForMap("select name, company_name from customers where id = ?", account.customerId))
            .containsEntry("name", "김민아")
            .containsEntry("company_name", "가온상사")
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_sessions where account_id = ? and revoked_at is null",
            Long::class.java,
            account.accountId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForMap(
            "select authentication_method, credential_version_snapshot from customer_sessions where session_token_digest = ?",
            CustomerAuthSecrets.digest(newCookie.value),
        )).containsEntry("authentication_method", "PASSWORD")
            .containsEntry("credential_version_snapshot", 1L)
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_consent_acceptances where account_id = ? and policy_id = ? and policy_version = 1",
            Long::class.java,
            account.accountId,
            POLICY_ID,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_COMPLETED'",
            Long::class.java,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_CONSENT_ACCEPTED'",
            Long::class.java,
        )).isEqualTo(1)

        currentCustomer(cookie(account.rawSession))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/customer-session-required"))
        currentCustomer(cookie(olderSession)).andExpect(status().isUnauthorized)
        currentCustomer(newCookie).andExpect(status().isOk)
        passwordLogin(email, RAW_PASSWORD, newCookie).andExpect(status().isOk)
        assertThat(jdbc.queryForObject("select requester_id from tickets where id = ?", UUID::class.java, ticketId))
            .isEqualTo(anonymousCustomerId)

        val auditMetadata = jdbc.queryForObject(
            "select coalesce(string_agg(metadata_json, ''), '') from admin_security_audit_events",
            String::class.java,
        )!!
        assertThat(auditMetadata).doesNotContain(email, RAW_PASSWORD, "가온상사", account.rawSession, newCookie.value)
        assertThat(output.all).doesNotContain(email, RAW_PASSWORD, "가온상사", account.rawSession, newCookie.value)
    }

    @Test
    fun `completion rejects csrf password accounts stale policies and stale sessions without mutation`() {
        insertCurrentRegistrationPolicy()
        val csrfAccount = insertAccount("completion-csrf@example.test")
        complete(csrfAccount, csrf = null)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/customer-csrf-rejected"))
        assertPasswordless(csrfAccount)

        val passwordAccount = insertAccount("completion-password@example.test", password = "existing synthetic password")
        complete(passwordAccount)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/customer-registration-conflict"))
        assertThat(jdbc.queryForObject(
            "select credential_version from customer_accounts where id = ?",
            Long::class.java,
            passwordAccount.accountId,
        )).isZero()

        val stalePolicyAccount = insertAccount("completion-stale-policy@example.test")
        complete(stalePolicyAccount, acceptedPolicies = """[{"policyKey":"registration-terms","version":2}]""")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/customer-registration-conflict"))
        assertPasswordless(stalePolicyAccount)

        val staleSessionAccount = insertAccount(
            email = "completion-stale-session@example.test",
            credentialVersion = 1,
            sessionCredentialVersion = 0,
        )
        currentCustomer(cookie(staleSessionAccount.rawSession))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/customer-session-required"))
        assertPasswordless(staleSessionAccount, credentialVersion = 1)
    }

    @Test
    fun `completion limiter returns generic 429 before a third conflicting attempt`() {
        insertCurrentRegistrationPolicy()
        val account = insertAccount("completion-rate@example.test")
        val stalePolicies = """[{"policyKey":"registration-terms","version":2}]"""
        repeat(2) {
            complete(account, acceptedPolicies = stalePolicies).andExpect(status().isConflict)
        }

        complete(account, acceptedPolicies = stalePolicies)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("/problems/customer-authentication-rate-limited"))
        assertPasswordless(account)
    }

    @Test
    fun `consent and audit persistence failures return 503 and preserve passwordless state and session`() {
        insertCurrentRegistrationPolicy()
        val account = insertAccount("completion-rollback@example.test")

        listOf("CONSENT", "AUDIT").forEach { failurePoint ->
            installFailureTrigger(failurePoint)
            try {
                complete(account)
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(jsonPath("$.type").value("/problems/customer-authentication-unavailable"))
            } finally {
                dropFailureTrigger(failurePoint)
            }
            assertPasswordless(account)
            assertThat(jdbc.queryForObject(
                "select count(*) from customer_consent_acceptances where account_id = ?",
                Long::class.java,
                account.accountId,
            )).isZero()
            assertThat(jdbc.queryForObject(
                "select count(*) from customer_sessions where account_id = ? and revoked_at is null",
                Long::class.java,
                account.accountId,
            )).isEqualTo(1)
        }
    }

    @Test
    fun `concurrent completion creates one password credential acceptance and active session`() {
        insertCurrentRegistrationPolicy()
        val account = insertAccount("completion-race@example.test")
        val executor = Executors.newFixedThreadPool(2)
        val statuses = try {
            executor.invokeAll(
                listOf(
                    Callable { complete(account).andReturn().response.status },
                    Callable { complete(account).andReturn().response.status },
                ),
            ).map { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(statuses.count { it == 200 }).isEqualTo(1)
        assertThat(statuses.filterNot { it == 200 }).allMatch { it == 401 || it == 409 }
        assertThat(jdbc.queryForObject(
            "select credential_version from customer_accounts where id = ?",
            Long::class.java,
            account.accountId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_consent_acceptances where account_id = ?",
            Long::class.java,
            account.accountId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from customer_sessions where account_id = ? and revoked_at is null",
            Long::class.java,
            account.accountId,
        )).isEqualTo(1)
    }

    private fun complete(
        account: TestAccount,
        csrf: String? = CustomerAuthSecrets.csrf(properties.csrfKey, account.rawSession),
        acceptedPolicies: String = """[{"policyKey":"registration-terms","version":1}]""",
    ) = mockMvc.perform(
        put("/api/v1/customer/me/registration")
            .with { request -> request.remoteAddr = "192.0.2.31"; request }
            .cookie(cookie(account.rawSession))
            .apply { if (csrf != null) header("X-CSRF-TOKEN", csrf) }
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "password": "$RAW_PASSWORD",
                  "displayName": "김민아",
                  "companyName": "가온상사",
                  "acceptedPolicies": $acceptedPolicies
                }
                """.trimIndent(),
            ),
    )

    private fun passwordLogin(email: String, password: String, previous: Cookie) = mockMvc.perform(
        post("/api/v1/customer/auth/password-sessions")
            .with { request -> request.remoteAddr = "192.0.2.32"; request }
            .cookie(previous)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"email":"$email","password":"$password"}"""),
    )

    private fun currentCustomer(cookie: Cookie) = mockMvc.perform(get("/api/v1/customer/me").cookie(cookie))

    private fun insertAccount(
        email: String,
        password: String? = null,
        credentialVersion: Long = 0,
        sessionCredentialVersion: Long = credentialVersion,
    ): TestAccount {
        val customerId = insertCustomer(email, verified = true, name = "기존 고객")
        val accountId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        val passwordHash = password?.let(passwordHasher::encode)
        jdbc.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version, password_hash, password_changed_at, credential_version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0, ?, ?, ?)
            """.trimIndent(),
            accountId,
            customerId,
            email,
            now,
            now,
            now,
            now,
            passwordHash?.encoded,
            passwordHash?.let { now },
            credentialVersion,
        )
        val rawSession = CustomerAuthSecrets.randomBearer()
        insertSession(
            accountId = accountId,
            rawSession = rawSession,
            authenticationMethod = if (password == null) "MAGIC_LINK" else "PASSWORD",
            credentialVersion = sessionCredentialVersion,
        )
        return TestAccount(customerId, accountId, email, rawSession)
    }

    private fun insertSession(
        accountId: UUID,
        rawSession: String,
        authenticationMethod: String = "MAGIC_LINK",
        credentialVersion: Long = 0,
    ) {
        val now = Instant.now()
        jdbc.update(
            """
            insert into customer_sessions
                (id, account_id, session_token_digest, created_at, last_activity_at, expires_at,
                 absolute_expires_at, revoked_at, authentication_method, credential_version_snapshot)
            values (?, ?, ?, ?, ?, ?, ?, null, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            CustomerAuthSecrets.digest(rawSession),
            Timestamp.from(now),
            Timestamp.from(now),
            Timestamp.from(now.plusSeconds(1_800)),
            Timestamp.from(now.plusSeconds(43_200)),
            authenticationMethod,
            credentialVersion,
        )
    }

    private fun insertCustomer(email: String, verified: Boolean, name: String): UUID {
        val customerId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customers (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            name,
            email,
            email,
            if (verified) now else null,
            now,
            now,
        )
        return customerId
    }

    private fun insertAnonymousTicket(customerId: UUID): UUID {
        val ticketId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into tickets
                (id, ticket_number, requester_id, kind, subject, status, priority,
                 group_id, assignee_id, channel, version, created_at, updated_at, solved_at)
            values (?, nextval('ticket_number_seq'), ?, 'CUSTOMER_REQUEST', 'Historical anonymous request',
                    'NEW', 'NORMAL', null, null, 'WEB', 0, ?, ?, null)
            """.trimIndent(),
            ticketId,
            customerId,
            now,
            now,
        )
        return ticketId
    }

    private fun insertCurrentRegistrationPolicy() {
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at)
            values (?, 'registration-owner@example.test', 'registration-owner@example.test',
                    'Registration Owner', 'ADMIN', 'ACTIVE', 'synthetic-hash', now(), now())
            """.trimIndent(),
            STAFF_ID,
        )
        jdbc.update(
            """
            insert into customer_consent_policies
                (id, policy_key, context, lifecycle, draft_title, draft_document_json,
                 draft_plain_text, draft_checksum_sha256, draft_required, draft_display_order,
                 draft_version, published_version, aggregate_version, created_at, updated_at)
            values (?, 'registration-terms', 'REGISTRATION', 'DRAFT', 'Synthetic terms',
                    '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                    'Synthetic', ?, true, 10, 1, null, 0, now(), now())
            """.trimIndent(),
            POLICY_ID,
            CHECKSUM,
        )
        jdbc.update(
            """
            insert into customer_consent_policy_versions
                (policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                 display_order, effective_at, published_by_staff_id, published_by_display, published_at)
            values (?, 1, 'Synthetic terms',
                    '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                    'Synthetic', ?, true, 10, now(), ?, 'Registration Owner', now())
            """.trimIndent(),
            POLICY_ID,
            CHECKSUM,
            STAFF_ID,
        )
        jdbc.update(
            "update customer_consent_policies set lifecycle = 'PUBLISHED', published_version = 1 where id = ?",
            POLICY_ID,
        )
    }

    private fun installFailureTrigger(failurePoint: String) {
        val target = if (failurePoint == "CONSENT") "customer_consent_acceptances" else "admin_security_audit_events"
        val condition = if (failurePoint == "AUDIT") {
            "if new.event_type = 'CUSTOMER_REGISTRATION_COMPLETED' then raise exception 'injected completion audit failure'; end if;"
        } else {
            "raise exception 'injected completion consent failure';"
        }
        jdbc.execute(
            """
            create or replace function fail_customer_completion_${failurePoint.lowercase()}()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin $condition return new; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_customer_completion_${failurePoint.lowercase()} before insert on $target " +
                "for each row execute function fail_customer_completion_${failurePoint.lowercase()}()",
        )
    }

    private fun dropFailureTrigger(failurePoint: String) {
        val target = if (failurePoint == "CONSENT") "customer_consent_acceptances" else "admin_security_audit_events"
        jdbc.execute("drop trigger if exists fail_customer_completion_${failurePoint.lowercase()} on $target")
        jdbc.execute("drop function if exists fail_customer_completion_${failurePoint.lowercase()}()")
    }

    private fun assertPasswordless(account: TestAccount, credentialVersion: Long = 0) {
        val state = jdbc.queryForMap(
            "select password_hash, password_changed_at, credential_version, version from customer_accounts where id = ?",
            account.accountId,
        )
        assertThat(state["password_hash"]).isNull()
        assertThat(state["password_changed_at"]).isNull()
        assertThat(state["credential_version"]).isEqualTo(credentialVersion)
        assertThat(state["version"]).isEqualTo(0L)
    }

    private fun assertSecureCookie(cookie: Cookie) {
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.secure).isTrue()
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax")
        assertThat(cookie.value).hasSize(43)
    }

    private fun cookie(rawSession: String) = Cookie(CUSTOMER_COOKIE, rawSession)

    private data class TestAccount(
        val customerId: UUID,
        val accountId: UUID,
        val email: String,
        val rawSession: String,
    )

    companion object {
        private const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"
        private const val RAW_PASSWORD = "synthetic completion password 🔐"
        private const val CHECKSUM = "ba3c91bf5b56ab63cb3105c7fa2950af6e651308c25f8af7429550bda8d33a4d"
        private val STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000008401")
        private val POLICY_ID = UUID.fromString("00000000-0000-4000-8000-000000008402")

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
