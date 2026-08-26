package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CreateCustomerConsentPolicy
import dev.deskseed.customerconsent.CustomerConsentConflictException
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentLifecycle
import dev.deskseed.customerconsent.CustomerConsentPolicyActor
import dev.deskseed.customerconsent.CustomerConsentPolicyAdministration
import dev.deskseed.customerconsent.CustomerConsentPolicyDraftInput
import dev.deskseed.customerconsent.CustomerConsentPreconditionFailedException
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.foundation.RequestSource
import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import dev.deskseed.knowledge.KnowledgeBlock
import dev.deskseed.organization.StaffAuthorityCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerConsentAdministrationIntegrationTest {
    @Autowired private lateinit var administration: CustomerConsentPolicyAdministration
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val staffId = UUID.fromString("00000000-0000-0000-0000-000000008101")

    @BeforeEach
    @AfterEach
    fun clearState() {
        jdbc.execute(
            "truncate table customer_registration_intent_consents, customer_registration_intents, " +
                "customer_consent_acceptances, customer_consent_policy_versions, " +
                "customer_consent_policies, admin_security_audit_events cascade",
        )
        jdbc.update("delete from staff_accounts where id = ?", staffId)
    }

    @Test
    fun `draft update publish archive preserves immutable history and metadata only audit`() {
        insertStaff()
        val created = administration.create(create("test-terms", CustomerConsentContext.REGISTRATION), actor())
        val updated = administration.updateDraft(created.id, 0, draft("개정안", "개정 본문"), actor())
        val first = administration.publish(created.id, 1, actor())
        administration.updateDraft(created.id, 2, draft("두 번째 개정안", "두 번째 본문"), actor())
        val second = administration.publish(created.id, 3, actor())
        val archived = administration.archive(created.id, 4, actor())
        val detail = administration.get(created.id, actor())

        assertThat(created.aggregateVersion).isZero()
        assertThat(updated.draft.version).isEqualTo(2)
        assertThat(first.publishedVersion?.version).isEqualTo(1)
        assertThat(second.publishedVersion?.version).isEqualTo(2)
        assertThat(archived.lifecycle).isEqualTo(CustomerConsentLifecycle.ARCHIVED)
        assertThat(detail.versions.map { it.version }).containsExactly(1, 2)
        assertThat(detail.versions.map { it.title }).containsExactly("개정안", "두 번째 개정안")
        val page = administration.list(
            CustomerConsentContext.REGISTRATION,
            CustomerConsentLifecycle.ARCHIVED,
            0,
            25,
            actor(),
        )
        assertThat(page.totalCount).isEqualTo(1)
        assertThat(page.items.single().id).isEqualTo(created.id)
        assertThat(detail.versions).allSatisfy { version ->
            assertThat(version.effectiveAt).isEqualTo(version.publishedAt)
        }

        val audits = jdbc.queryForList(
            "select event_type, metadata_json::text metadata from admin_security_audit_events order by occurred_at, id",
        )
        assertThat(audits.map { it["event_type"] }).containsExactlyInAnyOrder(
            "CUSTOMER_CONSENT_POLICY_CREATED",
            "CUSTOMER_CONSENT_POLICY_DRAFT_UPDATED",
            "CUSTOMER_CONSENT_POLICY_PUBLISHED",
            "CUSTOMER_CONSENT_POLICY_DRAFT_UPDATED",
            "CUSTOMER_CONSENT_POLICY_PUBLISHED",
            "CUSTOMER_CONSENT_POLICY_ARCHIVED",
        )
        assertThat(audits.joinToString()).doesNotContain("개정 본문", "두 번째 본문", "blocks", "plainText")
    }

    @Test
    fun `access stale write and audit failure are non mutating`() {
        insertStaff()
        assertThatThrownBy { administration.list(null, null, 0, 25, actor(isAdmin = false)) }
            .isInstanceOf(AccessDeniedException::class.java)
        assertThatThrownBy { administration.list(null, null, 0, 25, actor(authorities = emptySet())) }
            .isInstanceOf(AccessDeniedException::class.java)

        val created = administration.create(create("privacy", CustomerConsentContext.REGISTRATION), actor())
        assertThatThrownBy { administration.updateDraft(created.id, 99, draft("stale", "stale"), actor()) }
            .isInstanceOf(CustomerConsentPreconditionFailedException::class.java)
        assertThat(administration.get(created.id, actor()).draft.title).isEqualTo("초안")
        val auditCount = countAudits()
        assertThat(auditCount).isEqualTo(1)

        installFailingAuditTrigger()
        try {
            assertThatThrownBy { administration.updateDraft(created.id, 0, draft("rollback", "rollback"), actor()) }
                .isInstanceOf(CustomerConsentUnavailableException::class.java)
        } finally {
            jdbc.execute("drop trigger if exists fail_customer_consent_audit on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_customer_consent_audit()")
        }

        val unchanged = administration.get(created.id, actor())
        assertThat(unchanged.aggregateVersion).isZero()
        assertThat(unchanged.draft.title).isEqualTo("초안")
        assertThat(countAudits()).isEqualTo(auditCount)
    }

    @Test
    fun `context serialization allows only one concurrent publish at the cap boundary`() {
        insertStaff()
        repeat(19) { index ->
            val policy = administration.create(create("published-$index", CustomerConsentContext.REGISTRATION), actor())
            administration.publish(policy.id, 0, actor())
        }
        val candidates = listOf("candidate-a", "candidate-b").map { key ->
            administration.create(create(key, CustomerConsentContext.REGISTRATION), actor())
        }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = candidates.map { policy ->
                executor.submit<Result<Unit>> {
                    start.await()
                    runCatching { administration.publish(policy.id, 0, actor()); Unit }
                }
            }
            start.countDown()
            val results = futures.map { it.get() }

            assertThat(results.count(Result<Unit>::isSuccess)).isEqualTo(1)
            assertThat(results.mapNotNull(Result<Unit>::exceptionOrNull).single())
                .isInstanceOf(CustomerConsentConflictException::class.java)
            assertThat(
                jdbc.queryForObject(
                    "select count(*) from customer_consent_policies where context = 'REGISTRATION' and lifecycle = 'PUBLISHED'",
                    Long::class.java,
                ),
            ).isEqualTo(20)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun create(key: String, context: CustomerConsentContext) = CreateCustomerConsentPolicy(key, context, draft("초안", "본문"))

    private fun draft(title: String, text: String) = CustomerConsentPolicyDraftInput(
        title,
        CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Paragraph(text))),
        required = true,
        displayOrder = 10,
    )

    private fun actor(
        isAdmin: Boolean = true,
        authorities: Set<String> = setOf(StaffAuthorityCatalog.CUSTOMER_CONSENT_MANAGE),
    ) = CustomerConsentPolicyActor(
        staffId, "Consent Admin", isAdmin, authorities, RequestSource.ADMIN_UI,
        "request-consent-admin", "correlation-consent-admin",
    )

    private fun insertStaff() {
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at)
            values (?, 'consent-lifecycle@example.test', 'consent-lifecycle@example.test',
                    'Consent Admin', 'ADMIN', 'ACTIVE', 'synthetic-hash', now(), now())
            """.trimIndent(),
            staffId,
        )
    }

    private fun installFailingAuditTrigger() {
        jdbc.execute(
            """
            create or replace function fail_customer_consent_audit() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'CUSTOMER_CONSENT_POLICY_DRAFT_UPDATED' then
                    raise exception 'forced customer consent audit failure';
                end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_customer_consent_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_customer_consent_audit()",
        )
    }

    private fun countAudits(): Long = jdbc.queryForObject(
        "select count(*) from admin_security_audit_events",
        Long::class.java,
    ) ?: 0
}
