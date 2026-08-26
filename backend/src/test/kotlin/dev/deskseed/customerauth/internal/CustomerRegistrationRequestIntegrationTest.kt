package dev.deskseed.customerauth.internal

import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyContextLock
import dev.deskseed.outboundmail.internal.ProtectedMailContent
import dev.deskseed.outboundmail.internal.ProtectedMailContentCipher
import dev.deskseed.outboundmail.internal.ProtectedMailContentProperties
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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

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
    @Autowired private lateinit var oneTimeTokenService: OneTimeTokenService
    @Autowired private lateinit var intentStore: JdbcCustomerRegistrationIntentStore
    @Autowired private lateinit var policyContextLock: CustomerConsentPolicyContextLock
    @Autowired private lateinit var transactionTemplate: TransactionTemplate

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
    fun `registration request waits for account creation lock and creates no registration artifacts`() {
        val executor = Executors.newSingleThreadExecutor()
        val pendingRequest = AtomicReference<Future<CreatedCustomerRegistrationIntent?>>()
        try {
            transactionTemplate.executeWithoutResult {
                jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                    { _, _ -> Unit },
                    "customer-account:$RACING_EMAIL",
                )
                pendingRequest.set(
                    executor.submit<CreatedCustomerRegistrationIntent?> {
                        intentStore.replacePendingIfAccountAbsent(registrationIntent(RACING_EMAIL))
                    },
                )

                assertThatThrownBy { pendingRequest.get().get(1, TimeUnit.SECONDS) }
                    .isInstanceOf(TimeoutException::class.java)
                insertExistingAccount(RACING_EMAIL)
            }

            assertThat(pendingRequest.get().get(5, TimeUnit.SECONDS)).isNull()
        } finally {
            executor.shutdownNow()
        }
        assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_registration_intents where email_normalized = ?",
                Long::class.java,
                RACING_EMAIL,
            ),
        ).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customer_one_time_tokens", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from outbound_mail_intents", Long::class.java)).isZero()
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

    @Test
    fun `email token and matching browser proof activate one verified password account`(output: CapturedOutput) {
        val requested = requestRegistration(NEW_EMAIL, RAW_PASSWORD)
            .andExpect(status().isAccepted)
            .andReturn()
        val continuation = requireNotNull(requested.response.getCookie(REGISTRATION_COOKIE))
        val rawToken = registrationVerificationToken(NEW_EMAIL)

        val verified = verifyRegistration(rawToken, continuation)
            .andExpect(status().isNoContent)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andReturn()

        val expiredCookie = requireNotNull(verified.response.getCookie(REGISTRATION_COOKIE))
        assertThat(expiredCookie.value).isEmpty()
        assertThat(expiredCookie.maxAge).isZero()
        val account = jdbc.queryForMap(
            """
            select account.id, account.customer_id, account.email_normalized, account.status,
                   account.password_hash, account.credential_version,
                   customer.name, customer.company_name, customer.verified_at
              from customer_accounts account
              join customers customer on customer.id = account.customer_id
            """.trimIndent(),
        )
        assertThat(account)
            .containsEntry("email_normalized", NEW_EMAIL)
            .containsEntry("status", "ACTIVE")
            .containsEntry("name", "김민아")
            .containsEntry("company_name", "가온상사")
            .containsEntry("credential_version", 0L)
        assertThat(account["password_hash"].toString()).startsWith("\$argon2id\$")
        assertThat(account["verified_at"]).isNotNull()
        assertThat(
            jdbc.queryForMap(
                """
                select customer_id, account_id, policy_id, policy_version, context, source
                  from customer_consent_acceptances
                """.trimIndent(),
            ),
        ).containsEntry("customer_id", account["customer_id"])
            .containsEntry("account_id", account["id"])
            .containsEntry("policy_id", POLICY_ID)
            .containsEntry("policy_version", 1)
            .containsEntry("context", "REGISTRATION")
            .containsEntry("source", "CUSTOMER_PORTAL")
        assertThat(jdbc.queryForMap("select status, version from customer_registration_intents"))
            .containsEntry("status", "CONSUMED")
            .containsEntry("version", 1L)
        assertThat(
            jdbc.queryForObject("select consumed_at is not null from customer_one_time_tokens", Boolean::class.java),
        ).isTrue()
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_VERIFIED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_CONSENT_ACCEPTED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from customer_sessions", Long::class.java)).isZero()
        assertThat(auditMetadata()).doesNotContain(NEW_EMAIL, RAW_PASSWORD, "가온상사", rawToken, continuation.value)
        assertThat(output.all).doesNotContain(NEW_EMAIL, RAW_PASSWORD, "가온상사", rawToken, continuation.value)
    }

    @Test
    fun `browser proof mismatch rolls token consumption back and the matching browser can retry`() {
        val first = registrationProofs(NEW_EMAIL)
        val second = registrationProofs("other-registration@example.test")

        verifyRegistration(first.rawToken, second.continuation)
            .andExpect(status().isUnauthorized)
        assertPendingUnconsumed(NEW_EMAIL)

        verifyRegistration(first.rawToken, first.continuation)
            .andExpect(status().isNoContent)
        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_VERIFIED' and outcome = 'DENIED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `wrong purpose expiry and replay use generic 401 without a second account`() {
        val proofs = registrationProofs(NEW_EMAIL)
        val passwordlessToken = transactionTemplate.execute {
            oneTimeTokenService.generate(
                GenerateOneTimeTokenRequest("passwordless-purpose@example.test", Duration.ofMinutes(15)),
            ).tokenValue
        }

        verifyRegistration(passwordlessToken, proofs.continuation)
            .andExpect(status().isUnauthorized)
        assertThat(
            jdbc.queryForObject(
                "select consumed_at is null from customer_one_time_tokens where purpose = 'PASSWORDLESS_LOGIN'",
                Boolean::class.java,
            ),
        ).isTrue()

        jdbc.update(
            """
            update customer_one_time_tokens
               set expires_at = created_at + interval '1 millisecond'
             where purpose = 'EMAIL_VERIFICATION'
            """.trimIndent(),
        )
        verifyRegistration(proofs.rawToken, proofs.continuation)
            .andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isZero()

        val replayProofs = registrationProofs("replay-registration@example.test")
        verifyRegistration(replayProofs.rawToken, replayProofs.continuation)
            .andExpect(status().isNoContent)
        verifyRegistration(replayProofs.rawToken, replayProofs.continuation)
            .andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isEqualTo(1)
    }

    @Test
    fun `policy change and existing account race return conflict without replacing identity`() {
        val stale = registrationProofs(NEW_EMAIL)
        publishRegistrationPolicyVersionTwo()

        verifyRegistration(stale.rawToken, stale.continuation)
            .andExpect(status().isConflict)
        assertPendingUnconsumed(NEW_EMAIL)
        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isZero()

        clearState()
        insertCurrentRegistrationPolicy()
        val raced = registrationProofs(EXISTING_EMAIL)
        insertExistingAccount(EXISTING_EMAIL)
        verifyRegistration(raced.rawToken, raced.continuation)
            .andExpect(status().isConflict)

        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select password_hash is null from customer_accounts where email_normalized = ?",
                Boolean::class.java,
                EXISTING_EMAIL,
            ),
        ).isTrue()
        assertPendingUnconsumed(EXISTING_EMAIL)
        assertThat(jdbc.queryForObject("select count(*) from customer_consent_acceptances", Long::class.java)).isZero()
    }

    @Test
    fun `verification waits for registration policy mutation and revalidates the committed version`() {
        val proofs = registrationProofs(NEW_EMAIL)
        val executor = Executors.newSingleThreadExecutor()
        val verification = AtomicReference<Future<Int>>()
        try {
            transactionTemplate.executeWithoutResult {
                policyContextLock.lock(CustomerConsentContext.REGISTRATION)
                verification.set(
                    executor.submit<Int> {
                        verifyRegistration(proofs.rawToken, continuationCookie(proofs.continuation.value))
                            .andReturn().response.status
                    },
                )
                assertThatThrownBy { verification.get().get(1, TimeUnit.SECONDS) }
                    .isInstanceOf(TimeoutException::class.java)
                publishRegistrationPolicyVersionTwo()
            }

            assertThat(verification.get().get(5, TimeUnit.SECONDS)).isEqualTo(409)
        } finally {
            executor.shutdownNow()
        }
        assertPendingUnconsumed(NEW_EMAIL)
        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customer_consent_acceptances", Long::class.java)).isZero()
    }

    @Test
    fun `concurrent verification consumes proofs once and creates one account`() {
        val proofs = registrationProofs(NEW_EMAIL)
        val executor = Executors.newFixedThreadPool(2)
        val statuses = try {
            executor.invokeAll(
                listOf(
                    Callable { verifyRegistration(proofs.rawToken, continuationCookie(proofs.continuation.value)).andReturn().response.status },
                    Callable { verifyRegistration(proofs.rawToken, continuationCookie(proofs.continuation.value)).andReturn().response.status },
                ),
            ).map { it.get(15, TimeUnit.SECONDS) }.sorted()
        } finally {
            executor.shutdownNow()
        }

        assertThat(statuses).containsExactly(204, 401)
        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from customers", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from customer_consent_acceptances", Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("select count(*) from customer_registration_intents where status = 'CONSUMED'", Long::class.java))
            .isEqualTo(1)
    }

    @Test
    fun `verification audit failure returns 503 and rolls account acceptance and proof consumption back`() {
        val proofs = registrationProofs(NEW_EMAIL)
        jdbc.execute(
            """
            create or replace function fail_customer_registration_verification_audit()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected customer registration verification audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            create trigger fail_customer_registration_verification_audit
            before insert on admin_security_audit_events
            for each row when (new.event_type = 'CUSTOMER_REGISTRATION_VERIFIED')
            execute function fail_customer_registration_verification_audit()
            """.trimIndent(),
        )
        try {
            verifyRegistration(proofs.rawToken, proofs.continuation)
                .andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_customer_registration_verification_audit on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_customer_registration_verification_audit()")
        }

        assertThat(jdbc.queryForObject("select count(*) from customer_accounts", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customers", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from customer_consent_acceptances", Long::class.java)).isZero()
        assertPendingUnconsumed(NEW_EMAIL)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'CUSTOMER_REGISTRATION_REQUESTED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
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

    private fun verifyRegistration(rawToken: String, continuation: Cookie? = null) = mockMvc.perform(
        post("/api/v1/customer/registration-verifications")
            .apply { if (continuation != null) cookie(continuation) }
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"token":"$rawToken"}"""),
    )

    private fun registrationProofs(email: String): RegistrationProofs {
        val requested = requestRegistration(email, RAW_PASSWORD)
            .andExpect(status().isAccepted)
            .andReturn()
        return RegistrationProofs(
            registrationVerificationToken(email),
            requireNotNull(requested.response.getCookie(REGISTRATION_COOKIE)),
        )
    }

    private fun continuationCookie(value: String) = Cookie(REGISTRATION_COOKIE, value)

    private fun assertPendingUnconsumed(email: String) {
        assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_registration_intents where email_normalized = ? and status = 'PENDING'",
                Long::class.java,
                email,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select consumed_at is null from customer_one_time_tokens where email_normalized = ? and purpose = 'EMAIL_VERIFICATION'",
                Boolean::class.java,
                email,
            ),
        ).isTrue()
    }

    private fun publishRegistrationPolicyVersionTwo() {
        jdbc.update(
            """
            insert into customer_consent_policy_versions
                (policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                 display_order, effective_at, published_by_staff_id, published_by_display, published_at)
            values (?, 2, 'Synthetic terms v2',
                    '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                    'Synthetic', ?, true, 10, now(), ?, 'Registration Owner', now())
            """.trimIndent(),
            POLICY_ID,
            CHECKSUM,
            STAFF_ID,
        )
        jdbc.update(
            "update customer_consent_policies set published_version = 2, aggregate_version = aggregate_version + 1 where id = ?",
            POLICY_ID,
        )
    }

    private fun registrationVerificationToken(email: String): String {
        val stored = jdbc.queryForMap(
            """
            select id, protected_body_ciphertext, protected_body_nonce, protected_body_key_version
              from outbound_mail_intents
             where recipient_address = ?
               and template_key = 'CUSTOMER_REGISTRATION_VERIFICATION'
            """.trimIndent(),
            email,
        )
        val intentId = stored["id"] as UUID
        val content = ProtectedMailContent(
            ciphertext = stored["protected_body_ciphertext"] as ByteArray,
            nonce = stored["protected_body_nonce"] as ByteArray,
            keyVersion = stored["protected_body_key_version"] as String,
        )
        val plaintext = ProtectedMailContentCipher(
            ProtectedMailContentProperties(
                activeKeyVersion = "local-v1",
                keys = mapOf("local-v1" to PROTECTED_MAIL_KEY),
            ),
        ).decrypt(content, intentId)
        return plaintext.substringAfter("#token=").lineSequence().first().trim()
    }

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

    private fun registrationIntent(email: String) = NewCustomerRegistrationIntent(
        emailDisplay = email,
        passwordHash = CustomerPasswordHash.fromEncoded(
            "${'$'}argon2id${'$'}v=19${'$'}m=19456,t=2,p=1${'$'}synthetic-salt${'$'}synthetic-hash",
        ),
        displayName = "경합 고객",
        companyName = "경합 회사",
        policySelections = listOf(CustomerRegistrationPolicySelection(POLICY_ID, 1)),
        ttl = java.time.Duration.ofHours(24),
        context = dev.deskseed.foundation.CommandContext(
            source = dev.deskseed.foundation.RequestSource.CUSTOMER_PORTAL,
            requestId = "registration-account-race",
            correlationId = "registration-account-race",
            commandId = "registration-account-race",
        ),
    )

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

    private data class RegistrationProofs(
        val rawToken: String,
        val continuation: Cookie,
    )

    companion object {
        private const val NEW_EMAIL = "new-registration@example.test"
        private const val EXISTING_EMAIL = "existing-registration@example.test"
        private const val RACING_EMAIL = "racing-registration@example.test"
        private const val RAW_PASSWORD = "synthetic registration password 🔐"
        private const val REGISTRATION_COOKIE = "DESKSEED_CUSTOMER_REGISTRATION"
        private const val PROTECTED_MAIL_KEY = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="
        private const val CHECKSUM = "ba3c91bf5b56ab63cb3105c7fa2950af6e651308c25f8af7429550bda8d33a4d"
        private val STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000008301")
        private val POLICY_ID = UUID.fromString("00000000-0000-4000-8000-000000008302")

        @Container
        @JvmStatic
        val redisContainer = GenericContainer(DockerImageName.parse("redis:8.2.9-alpine"))
            .withExposedPorts(6379)

        @DynamicPropertySource
        @JvmStatic
        fun redisProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.host", redisContainer::getHost)
            registry.add("spring.data.redis.port") { redisContainer.getMappedPort(6379) }
        }
    }
}
