package dev.deskseed.customerauth.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = ["deskseed.staff-auth.bootstrap.enabled=false"],
)
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerCredentialPersistenceIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var hasher: CustomerPasswordHasher
    @Autowired private lateinit var intentStore: JdbcCustomerRegistrationIntentStore
    @Autowired private lateinit var tokenService: JdbcDigestOneTimeTokenService
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var staffPasswordEncoder: PasswordEncoder

    @BeforeEach
    fun setUp() {
        clearState()
        insertPolicy()
        insertPasswordResetAccount()
    }

    @AfterEach
    fun tearDown() = clearState()

    @Test
    fun `pending registration replacement keeps only digests and immutable consent selections`() {
        val rawPassword = "registration password 🔐"
        val passwordHash = hasher.encode(rawPassword)
        val first = intentStore.replacePending(registration(passwordHash, "request-first"))

        val stored = jdbc.queryForMap(
            """
            select email_normalized, password_hash, display_name, company_name,
                   continuation_secret_digest, status, request_id, correlation_id
              from customer_registration_intents
             where id = ?
            """.trimIndent(),
            first.id,
        )
        assertThat(stored)
            .containsEntry("email_normalized", REGISTRATION_EMAIL)
            .containsEntry("password_hash", passwordHash.encoded)
            .containsEntry("display_name", "등록 고객")
            .containsEntry("company_name", "등록 회사")
            .containsEntry("status", "PENDING")
            .containsEntry("request_id", "request-first")
            .containsEntry("correlation_id", "correlation-auth")
        assertThat(stored["continuation_secret_digest"])
            .isEqualTo(CustomerAuthSecrets.digest(first.rawContinuationSecret))
            .isNotEqualTo(first.rawContinuationSecret)
        assertThat(
            jdbc.queryForMap(
                """
                select policy_id, policy_version, context
                  from customer_registration_intent_consents
                 where intent_id = ?
                """.trimIndent(),
                first.id,
            ),
        ).containsEntry("policy_id", POLICY_ID)
            .containsEntry("policy_version", 1)
            .containsEntry("context", "REGISTRATION")

        val wrongProof = transactionTemplate.execute {
            intentStore.lockPendingByProof(first.id, "wrong-continuation-proof")
        }
        assertThat(wrongProof).isNull()
        assertThat(
            transactionTemplate.execute {
                intentStore.lockPendingByProof(first.id, "x".repeat(257))
            },
        ).isNull()
        val pending = requireNotNull(
            transactionTemplate.execute {
                intentStore.lockPendingByProof(first.id, first.rawContinuationSecret)
            },
        )
        assertThat(pending.passwordHash).isEqualTo(passwordHash)
        assertThat(pending.policySelections)
            .containsExactly(CustomerRegistrationPolicySelection(POLICY_ID, 1))

        val replacement = intentStore.replacePending(registration(passwordHash, "request-replacement"))
        assertThat(replacement.id).isNotEqualTo(first.id)
        assertThat(jdbc.queryForMap("select status, version from customer_registration_intents where id = ?", first.id))
            .containsEntry("status", "CANCELLED")
            .containsEntry("version", 1L)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_registration_intents where email_normalized = ? and status = 'PENDING'",
                Long::class.java,
                REGISTRATION_EMAIL,
            ),
        ).isEqualTo(1)
        assertThat(databaseText())
            .doesNotContain(rawPassword)
            .doesNotContain(first.rawContinuationSecret)
            .doesNotContain(replacement.rawContinuationSecret)
        assertThat(registration(passwordHash, "request-protected").toString())
            .doesNotContain(REGISTRATION_EMAIL)
            .doesNotContain("등록 회사")
        assertThat(first.toString()).doesNotContain(first.rawContinuationSecret)
        assertThat(pending.toString()).doesNotContain(REGISTRATION_EMAIL)
    }

    @Test
    fun `concurrent replacements serialize to one pending intent`() {
        val passwordHash = hasher.encode("concurrent registration password")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { index ->
                executor.submit<CreatedCustomerRegistrationIntent> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    intentStore.replacePending(registration(passwordHash, "request-concurrent-$index"))
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(futures.map { it.get(10, TimeUnit.SECONDS).id }).doesNotHaveDuplicates()
        } finally {
            executor.shutdownNow()
        }

        assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_registration_intents where email_normalized = ? and status = 'PENDING'",
                Long::class.java,
                REGISTRATION_EMAIL,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_registration_intents where email_normalized = ? and status = 'CANCELLED'",
                Long::class.java,
                REGISTRATION_EMAIL,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `failed replacement rolls back cancellation of the prior pending intent`() {
        val passwordHash = hasher.encode("rollback registration password")
        val existing = intentStore.replacePending(registration(passwordHash, "request-existing"))
        val invalidSelection = registration(passwordHash, "request-invalid").copy(
            policySelections = listOf(CustomerRegistrationPolicySelection(UUID.randomUUID(), 1)),
        )

        assertThatThrownBy { intentStore.replacePending(invalidSelection) }
            .isInstanceOf(DataIntegrityViolationException::class.java)

        assertThat(jdbc.queryForMap("select status, version from customer_registration_intents where id = ?", existing.id))
            .containsEntry("status", "PENDING")
            .containsEntry("version", 0L)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from customer_registration_intents where email_normalized = ?",
                Long::class.java,
                REGISTRATION_EMAIL,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `purpose mismatch cannot consume another one-time token kind`() {
        val created = intentStore.replacePending(
            registration(hasher.encode("purpose isolated password"), "request-purpose"),
        )
        val verification = tokenService.generate(
            emailDisplay = REGISTRATION_EMAIL,
            target = CustomerOneTimeTokenTarget.EmailVerification(created.id),
            ttl = Duration.ofHours(24),
            context = context("token-verification"),
        )

        assertThat(
            tokenService.consume(verification.rawToken, CustomerOneTimeTokenPurpose.PASSWORD_RESET),
        ).isNull()
        assertThat(
            jdbc.queryForObject(
                "select consumed_at is null from customer_one_time_tokens where id = ?",
                Boolean::class.java,
                verification.id,
            ),
        ).isTrue()

        val consumedVerification = requireNotNull(
            tokenService.consume(
                verification.rawToken,
                CustomerOneTimeTokenPurpose.EMAIL_VERIFICATION,
            ),
        )
        assertThat(consumedVerification.purpose).isEqualTo(CustomerOneTimeTokenPurpose.EMAIL_VERIFICATION)
        assertThat(consumedVerification.registrationIntentId).isEqualTo(created.id)
        assertThat(consumedVerification.accountId).isNull()
        assertThat(
            tokenService.consume(verification.rawToken, CustomerOneTimeTokenPurpose.EMAIL_VERIFICATION),
        ).isNull()

        val reset = tokenService.generate(
            emailDisplay = RESET_EMAIL,
            target = CustomerOneTimeTokenTarget.PasswordReset(ACCOUNT_ID),
            ttl = Duration.ofMinutes(30),
            context = context("token-reset"),
        )
        val consumedReset = tokenService.consume(reset.rawToken, CustomerOneTimeTokenPurpose.PASSWORD_RESET)
        assertThat(consumedReset?.accountId).isEqualTo(ACCOUNT_ID)
        assertThat(consumedReset?.registrationIntentId).isNull()
        assertThat(databaseText())
            .doesNotContain(verification.rawToken)
            .doesNotContain(reset.rawToken)
        assertThat(verification.toString()).doesNotContain(verification.rawToken).doesNotContain(REGISTRATION_EMAIL)
        assertThat(consumedVerification.toString()).doesNotContain(REGISTRATION_EMAIL)
    }

    @Test
    fun `customer Argon2 component does not replace the staff BCrypt bean`() {
        assertThat(staffPasswordEncoder).isInstanceOf(BCryptPasswordEncoder::class.java)
    }

    @Test
    fun `locked pending proof transitions to consumed once by expected version`() {
        val created = intentStore.replacePending(
            registration(hasher.encode("single consume password"), "request-consume"),
        )

        val first = transactionTemplate.execute {
            val pending = requireNotNull(intentStore.lockPendingByProof(created.id, created.rawContinuationSecret))
            assertThat(intentStore.markConsumed(pending.id, pending.version)).isTrue()
            intentStore.markConsumed(pending.id, pending.version)
        }
        assertThat(first).isFalse()
        assertThat(
            transactionTemplate.execute {
                intentStore.lockPendingByProof(created.id, created.rawContinuationSecret)
            },
        ).isNull()
        assertThat(jdbc.queryForMap("select status, version from customer_registration_intents where id = ?", created.id))
            .containsEntry("status", "CONSUMED")
            .containsEntry("version", 1L)
    }

    private fun registration(
        passwordHash: CustomerPasswordHash,
        requestId: String,
    ) = NewCustomerRegistrationIntent(
        emailDisplay = REGISTRATION_EMAIL,
        passwordHash = passwordHash,
        displayName = "등록 고객",
        companyName = "등록 회사",
        policySelections = listOf(CustomerRegistrationPolicySelection(POLICY_ID, 1)),
        ttl = Duration.ofHours(24),
        context = context(requestId),
    )

    private fun context(requestId: String) = CommandContext(
        source = RequestSource.CUSTOMER_PORTAL,
        requestId = requestId,
        correlationId = "correlation-auth",
        commandId = "command-$requestId",
    )

    private fun insertPolicy() {
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at)
            values (?, 'auth-owner@example.test', 'auth-owner@example.test', 'Auth Owner',
                    'ADMIN', 'ACTIVE', 'synthetic-hash', now(), now())
            """.trimIndent(),
            STAFF_ID,
        )
        jdbc.update(
            """
            insert into customer_consent_policies
                (id, policy_key, context, lifecycle, draft_title, draft_document_json,
                 draft_plain_text, draft_checksum_sha256, draft_required, draft_display_order,
                 draft_version, published_version, aggregate_version, created_at, updated_at)
            values (?, 'registration-test', 'REGISTRATION', 'DRAFT', 'Synthetic terms',
                    '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                    'Synthetic', ?, true, 10, 1, null, 0, now(), now())
            """.trimIndent(),
            POLICY_ID,
            "f".repeat(64),
        )
        jdbc.update(
            """
            insert into customer_consent_policy_versions
                (policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                 display_order, effective_at, published_by_staff_id, published_by_display, published_at)
            values (?, 1, 'Synthetic terms',
                    '{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"Synthetic"}]}'::jsonb,
                    'Synthetic', ?, true, 10, now(), ?, 'Auth Owner', now())
            """.trimIndent(),
            POLICY_ID,
            "f".repeat(64),
            STAFF_ID,
        )
    }

    private fun insertPasswordResetAccount() {
        val now = java.sql.Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into customers
                (id, name, email_normalized, email_display, verified_at, created_at, updated_at)
            values (?, 'Reset Customer', ?, ?, ?, ?, ?)
            """.trimIndent(),
            CUSTOMER_ID,
            RESET_EMAIL,
            RESET_EMAIL,
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
            ACCOUNT_ID,
            CUSTOMER_ID,
            RESET_EMAIL,
            now,
            now,
            now,
            now,
        )
    }

    private fun databaseText(): String = jdbc.queryForObject(
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

    private fun clearState() {
        jdbc.execute(
            """
            truncate table customer_one_time_tokens, customer_registration_intent_consents,
                customer_registration_intents, customer_consent_acceptances,
                customer_consent_policy_versions, customer_consent_policies,
                customer_sessions, customer_accounts, customers, staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    private companion object {
        private const val REGISTRATION_EMAIL = "new-customer@example.test"
        private const val RESET_EMAIL = "reset-customer@example.test"
        private val STAFF_ID = UUID.fromString("00000000-0000-4000-8000-000000008201")
        private val POLICY_ID = UUID.fromString("00000000-0000-4000-8000-000000008202")
        private val CUSTOMER_ID = UUID.fromString("00000000-0000-4000-8000-000000008203")
        private val ACCOUNT_ID = UUID.fromString("00000000-0000-4000-8000-000000008204")
    }
}
