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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.customer-auth.registration-verification-ttl=24h",
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
class CustomerRegistrationRequestIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var redis: StringRedisTemplate

    @BeforeEach
    fun setUp() {
        redis.keys("deskseed:customer-auth:limiter:*").takeIf { it.isNotEmpty() }?.let(redis::delete)
        clearState()
        insertCurrentRegistrationPolicy()
    }

    @Test
    fun `new email receives generic 202 and persists only protected registration proofs`(output: CapturedOutput) {
        val result = requestRegistration(NEW_EMAIL, RAW_PASSWORD)
            .andExpect(status().isAccepted)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andReturn()

        assertThat(result.response.contentAsString).isEqualTo("{\"accepted\":true}")
        val cookie = requireNotNull(result.response.getCookie(REGISTRATION_COOKIE))
        assertContinuationCookie(cookie)

        val intent = jdbc.queryForMap(
            """
            select id, email_normalized, password_hash, display_name, company_name,
                   continuation_secret_digest, status
              from customer_registration_intents
            """.trimIndent(),
        )
        assertThat(intent)
            .containsEntry("email_normalized", NEW_EMAIL)
            .containsEntry("display_name", "김민아")
            .containsEntry("company_name", "가온상사")
            .containsEntry("status", "PENDING")
        assertThat(intent["password_hash"].toString())
            .startsWith("\$argon2id\$")
            .doesNotContain(RAW_PASSWORD)
        assertThat(intent["continuation_secret_digest"])
            .isEqualTo(CustomerAuthSecrets.digest(cookie.value))

        assertThat(
            jdbc.queryForMap(
                """
                select purpose, registration_intent_id, token_digest, consumed_at
                  from customer_one_time_tokens
                """.trimIndent(),
            ),
        ).containsEntry("purpose", "EMAIL_VERIFICATION")
            .containsEntry("registration_intent_id", intent["id"])
            .containsEntry("consumed_at", null)
        assertThat(
            jdbc.queryForObject("select token_digest from customer_one_time_tokens", String::class.java),
        ).hasSize(64)

        assertThat(
            jdbc.queryForMap(
                """
                select template_key, text_body, protected_body_ciphertext is not null as protected
                  from outbound_mail_intents
                """.trimIndent(),
            ),
        ).containsEntry("template_key", "CUSTOMER_REGISTRATION_VERIFICATION")
            .containsEntry("text_body", "[protected customer authentication content]")
            .containsEntry("protected", true)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_REQUESTED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(auditMetadata()).doesNotContain(NEW_EMAIL, RAW_PASSWORD, "가온상사", cookie.value)
        assertThat(output.all).doesNotContain(NEW_EMAIL, RAW_PASSWORD, "가온상사", cookie.value)
        assertThat(databaseAuthMaterial()).doesNotContain(RAW_PASSWORD, cookie.value)
    }

    @Test
    fun `existing account receives the same generic response and cookie shape without registration side effects`() {
        insertExistingAccount(EXISTING_EMAIL)

        val newResponse = requestRegistration(NEW_EMAIL, RAW_PASSWORD).andExpect(status().isAccepted).andReturn().response
        val existingResponse = requestRegistration(EXISTING_EMAIL, RAW_PASSWORD).andExpect(status().isAccepted).andReturn().response

        assertThat(existingResponse.contentAsString).isEqualTo(newResponse.contentAsString)
        val newCookie = requireNotNull(newResponse.getCookie(REGISTRATION_COOKIE))
        val existingCookie = requireNotNull(existingResponse.getCookie(REGISTRATION_COOKIE))
        assertContinuationCookie(existingCookie)
        assertThat(existingCookie.value).hasSameSizeAs(newCookie.value).isNotEqualTo(newCookie.value)
        assertThat(jdbc.queryForObject("select count(*) from customer_registration_intents", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_REQUESTED'",
                Long::class.java,
            ),
        ).isEqualTo(2)
        assertThat(auditMetadata()).doesNotContain(NEW_EMAIL, EXISTING_EMAIL, RAW_PASSWORD)
    }

    @Test
    fun `stale missing and duplicate registration policies fail before registration persistence`() {
        listOf(
            "[{\"policyKey\":\"registration-terms\",\"version\":2}]",
            "[{\"policyKey\":\"unknown-policy\",\"version\":1}]",
            "[{\"policyKey\":\"registration-terms\",\"version\":1},{\"policyKey\":\"registration-terms\",\"version\":1}]",
        ).forEachIndexed { index, acceptedPolicies ->
            requestRegistration("invalid-policy-$index@example.test", RAW_PASSWORD, acceptedPolicies)
                .andExpect(status().isBadRequest)
        }

        assertThat(jdbc.queryForObject("select count(*) from customer_registration_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events", Long::class.java)).isZero()
    }

    @Test
    fun `bean validation failures use the stable registration 400 problem`() {
        mockMvc.perform(
            post("/api/v1/customer/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "email": "invalid@example.test",
                      "password": "synthetic registration password",
                      "displayName": "",
                      "companyName": "합성 회사",
                      "acceptedPolicies": [{"policyKey":"registration-terms","version":1}]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))

        assertNoRegistrationEffects()
    }

    @Test
    fun `registration fails closed when no current required policy exists`() {
        jdbc.update("update customer_consent_policies set lifecycle = 'ARCHIVED' where id = ?", POLICY_ID)

        requestRegistration(NEW_EMAIL, RAW_PASSWORD)
            .andExpect(status().isServiceUnavailable)

        assertThat(jdbc.queryForObject("select count(*) from customer_registration_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events", Long::class.java)).isZero()
    }

    @Test
    fun `exhausted registration budget returns generic 429 with retry after`() {
        requestRegistration(NEW_EMAIL, RAW_PASSWORD).andExpect(status().isAccepted)
        requestRegistration(NEW_EMAIL, RAW_PASSWORD).andExpect(status().isAccepted)
        val denied = requestRegistration(NEW_EMAIL, RAW_PASSWORD)
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andReturn()

        assertThat(denied.response.contentAsString)
            .contains("/problems/customer-authentication-rate-limited")
            .doesNotContain(NEW_EMAIL)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_REQUESTED'",
                Long::class.java,
            ),
        ).isEqualTo(3)
        assertThat(auditMetadata()).doesNotContain(NEW_EMAIL, RAW_PASSWORD)
    }

    @Test
    fun `required audit failure returns 503 and rolls back intent token and outbox`() {
        jdbc.execute(
            """
            create or replace function fail_customer_registration_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected customer registration audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            create trigger fail_customer_registration_audit
            before insert on admin_security_audit_events
            for each row when (new.event_type = 'CUSTOMER_REGISTRATION_REQUESTED')
            execute function fail_customer_registration_audit()
            """.trimIndent(),
        )
        try {
            requestRegistration(NEW_EMAIL, RAW_PASSWORD)
                .andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_customer_registration_audit on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_customer_registration_audit()")
        }

        assertNoRegistrationEffects()
        assertThat(redis.keys("deskseed:customer-auth:limiter:*")).isNotEmpty
    }

    @Test
    fun `outbox persistence failure returns 503 and rolls back intent token and audit`() {
        jdbc.execute(
            """
            create or replace function fail_customer_registration_mail()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected customer registration mail failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            create trigger fail_customer_registration_mail
            before insert on outbound_mail_intents
            for each row execute function fail_customer_registration_mail()
            """.trimIndent(),
        )
        try {
            requestRegistration(NEW_EMAIL, RAW_PASSWORD)
                .andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_customer_registration_mail on outbound_mail_intents")
            jdbc.execute("drop function if exists fail_customer_registration_mail()")
        }

        assertNoRegistrationEffects()
        assertThat(redis.keys("deskseed:customer-auth:limiter:*")).isNotEmpty
    }

    private fun requestRegistration(
        email: String,
        password: String,
        acceptedPolicies: String = "[{\"policyKey\":\"registration-terms\",\"version\":1}]",
    ) = mockMvc.perform(
        post("/api/v1/customer/registrations")
            .with { request -> request.remoteAddr = "192.0.2.20"; request }
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "email": "$email",
                  "password": "$password",
                  "displayName": "김민아",
                  "companyName": "가온상사",
                  "acceptedPolicies": $acceptedPolicies
                }
                """.trimIndent(),
            ),
    )

    private fun assertContinuationCookie(cookie: Cookie) {
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.secure).isTrue()
        assertThat(cookie.path).isEqualTo("/api/v1/customer/registration-verifications")
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax")
        assertThat(cookie.maxAge).isEqualTo(86_400)
        assertThat(cookie.value).hasSize(43)
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

    private fun insertExistingAccount(email: String) {
        val customerId = UUID.randomUUID()
        val accountId = UUID.randomUUID()
        val now = java.sql.Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customers
                (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Existing Customer', ?, ?, ?, ?, ?)
            """.trimIndent(),
            customerId,
            email,
            email,
            now,
            now,
            now,
        )
        jdbc.update(
            """
            insert into customer_accounts
                (id, customer_id, email_normalized, status, verified_at, last_login_at,
                 created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
            """.trimIndent(),
            accountId,
            customerId,
            email,
            now,
            now,
            now,
            now,
        )
    }

    private fun auditMetadata(): String = jdbc.queryForObject(
        "select coalesce(string_agg(metadata_json, ''), '') from admin_security_audit_events",
        String::class.java,
    )!!

    private fun databaseAuthMaterial(): String = jdbc.queryForObject(
        """
        select coalesce(string_agg(value, ''), '')
          from (
            select password_hash || continuation_secret_digest as value from customer_registration_intents
            union all
            select token_digest from customer_one_time_tokens
          ) stored_auth_material
        """.trimIndent(),
        String::class.java,
    )!!

    private fun assertNoRegistrationEffects() {
        assertThat(jdbc.queryForObject("select count(*) from customer_registration_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from admin_security_audit_events", Long::class.java)).isZero()
    }

    private fun clearState() {
        jdbc.execute(
            """
            truncate table
                outbound_mail_delivery_events, outbound_mail_attempts, outbound_mail_intents,
                customer_one_time_tokens, customer_registration_intent_consents,
                customer_registration_intents, customer_consent_acceptances,
                customer_consent_policy_versions, customer_consent_policies,
                customer_sessions, customer_accounts, customers, admin_security_audit_events,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    companion object {
        private const val NEW_EMAIL = "new-registration@example.test"
        private const val EXISTING_EMAIL = "existing-registration@example.test"
        private const val RAW_PASSWORD = "synthetic registration password 🔐"
        private const val REGISTRATION_COOKIE = "DESKSEED_CUSTOMER_REGISTRATION"
        private const val CHECKSUM = "ba3c91bf5b56ab63cb3105c7fa2950af6e651308c25f8af7429550bda8d33a4d"
        private val STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000008301")
        private val POLICY_ID = UUID.fromString("00000000-0000-4000-8000-000000008302")

        @Container
        @JvmStatic
        val redisContainer = GenericContainer(DockerImageName.parse("redis:8.2.7-alpine"))
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
        }
    }
}
