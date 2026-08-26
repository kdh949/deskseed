package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CanonicalCustomerConsentDocumentCodec
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyLifecycle
import dev.deskseed.knowledge.CanonicalKnowledgeDocument
import dev.deskseed.knowledge.KnowledgeBlock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class CustomerConsentProjectionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val documents = CanonicalCustomerConsentDocumentCodec()
    private lateinit var publisherId: UUID

    @BeforeEach
    fun setUp() {
        clearState()
        publisherId = insertPublisher()
    }

    @AfterEach
    fun tearDown() = clearState()

    @Test
    fun `anonymous projection returns only immutable current versions in stable display order`() {
        insertPolicy(
            key = "registration-privacy",
            context = CustomerConsentContext.REGISTRATION,
            lifecycle = CustomerConsentPolicyLifecycle.PUBLISHED,
            publishedTitle = "개인정보 처리 동의",
            publishedText = "공개된 개인정보 처리 조건",
            draftText = "아직 공개하지 않은 개인정보 개정안",
            displayOrder = 10,
        )
        insertPolicy(
            key = "registration-terms",
            context = CustomerConsentContext.REGISTRATION,
            lifecycle = CustomerConsentPolicyLifecycle.PUBLISHED,
            publishedTitle = "서비스 이용 조건",
            publishedText = "공개된 서비스 이용 조건",
            displayOrder = 5,
        )
        insertPolicy(
            key = "archived-registration",
            context = CustomerConsentContext.REGISTRATION,
            lifecycle = CustomerConsentPolicyLifecycle.ARCHIVED,
            publishedTitle = "중단된 정책",
            publishedText = "중단된 정책 본문",
            displayOrder = 0,
        )
        insertPolicy(
            key = "draft-registration",
            context = CustomerConsentContext.REGISTRATION,
            lifecycle = CustomerConsentPolicyLifecycle.DRAFT,
            publishedTitle = "초안 정책",
            publishedText = "초안 정책 본문",
            displayOrder = 0,
        )
        insertPolicy(
            key = "request-policy",
            context = CustomerConsentContext.REQUEST_SUBMISSION,
            lifecycle = CustomerConsentPolicyLifecycle.PUBLISHED,
            publishedTitle = "문의 정책",
            publishedText = "문의 정책 본문",
            displayOrder = 0,
        )

        val result = mockMvc.perform(
            get("/api/v1/customer/consent-policies").queryParam("context", "REGISTRATION"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn()

        val response = objectMapper.readTree(result.response.contentAsString)
        assertExactProperties(response, "context", "policies")
        assertThat(response.get("context").asString()).isEqualTo("REGISTRATION")
        val policies = response.get("policies")
        assertThat(policies.size()).isEqualTo(2)
        assertThat(policies.values().map { it.get("policyKey").asString() })
            .containsExactly("registration-terms", "registration-privacy")

        policies.values().forEach { policy ->
            assertExactProperties(
                policy,
                "policyId",
                "policyKey",
                "version",
                "title",
                "document",
                "checksumSha256",
                "required",
                "displayOrder",
                "effectiveAt",
            )
            assertExactProperties(policy.get("document"), "schemaVersion", "blocks")
            policy.get("document").get("blocks").values().forEach { block ->
                assertExactProperties(block, "type", "text")
            }
            assertThat(policy.get("version").asInt()).isEqualTo(1)
            assertThat(policy.get("checksumSha256").asString()).matches("^[a-f0-9]{64}$")
        }

        val privacy = policies.get(1)
        assertThat(privacy.get("title").asString()).isEqualTo("개인정보 처리 동의")
        assertThat(privacy.get("document").get("blocks").get(0).get("text").asString())
            .isEqualTo("공개된 개인정보 처리 조건")
        assertThat(result.response.contentAsString)
            .doesNotContain("아직 공개하지 않은", "중단된 정책", "draft", "plainText", "publishedBy", "history")
    }

    @Test
    fun `missing or unknown context returns bounded no store validation problem`() {
        mockMvc.perform(get("/api/v1/customer/consent-policies"))
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-request-invalid"))

        mockMvc.perform(
            get("/api/v1/customer/consent-policies").queryParam("context", "UNKNOWN"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-request-invalid"))
    }

    @Test
    fun `persisted document integrity mismatch fails closed without projecting policy data`() {
        insertPolicy(
            key = "corrupt-registration",
            context = CustomerConsentContext.REGISTRATION,
            lifecycle = CustomerConsentPolicyLifecycle.PUBLISHED,
            publishedTitle = "손상된 정책",
            publishedText = "손상 전 정책 본문",
            displayOrder = 0,
            checksumOverride = "0".repeat(64),
        )

        mockMvc.perform(
            get("/api/v1/customer/consent-policies").queryParam("context", "REGISTRATION"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-unavailable"))
            .andExpect(jsonPath("$.detail").value("현재 고객 동의 정책을 안전하게 조회할 수 없습니다."))
    }

    @Test
    fun `more than twenty current rows fails closed instead of truncating projection`() {
        repeat(21) { index ->
            insertPolicy(
                key = "registration-policy-${index.toString().padStart(2, '0')}",
                context = CustomerConsentContext.REGISTRATION,
                lifecycle = CustomerConsentPolicyLifecycle.PUBLISHED,
                publishedTitle = "등록 정책 $index",
                publishedText = "등록 정책 본문 $index",
                displayOrder = index,
            )
        }

        mockMvc.perform(
            get("/api/v1/customer/consent-policies").queryParam("context", "REGISTRATION"),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-unavailable"))
    }

    private fun insertPolicy(
        key: String,
        context: CustomerConsentContext,
        lifecycle: CustomerConsentPolicyLifecycle,
        publishedTitle: String,
        publishedText: String,
        displayOrder: Int,
        draftText: String = publishedText,
        checksumOverride: String? = null,
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.parse("2026-08-26T01:02:03Z").plusSeconds(displayOrder.toLong())
        val draft = validated(draftText)
        jdbc.update(
            """
            insert into customer_consent_policies (
                id, policy_key, context, lifecycle, draft_title, draft_document_json, draft_plain_text,
                draft_checksum_sha256, draft_required, draft_display_order, draft_version,
                published_version, aggregate_version, created_at, updated_at
            ) values (?, ?, ?, 'DRAFT', ?, cast(? as jsonb), ?, ?, true, ?, 1, null, 0, ?, ?)
            """.trimIndent(),
            id,
            key,
            context.name,
            "$publishedTitle 개정안",
            objectMapper.writeValueAsString(documents.encode(draft.document)),
            draft.plainText,
            draft.checksumSha256,
            displayOrder,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        if (lifecycle != CustomerConsentPolicyLifecycle.DRAFT) {
            val published = validated(publishedText)
            jdbc.update(
                """
                insert into customer_consent_policy_versions (
                    policy_id, version, title, document_json, plain_text, checksum_sha256, required,
                    display_order, effective_at, published_by_staff_id, published_by_display, published_at
                ) values (?, 1, ?, cast(? as jsonb), ?, ?, true, ?, ?, ?, '합성 테스트 관리자', ?)
                """.trimIndent(),
                id,
                publishedTitle,
                objectMapper.writeValueAsString(documents.encode(published.document)),
                published.plainText,
                checksumOverride ?: published.checksumSha256,
                displayOrder,
                Timestamp.from(now),
                publisherId,
                Timestamp.from(now),
            )
            jdbc.update(
                "update customer_consent_policies set lifecycle = ?, published_version = 1 where id = ?",
                lifecycle.name,
                id,
            )
        }
        return id
    }

    private fun validated(text: String) = documents.validateForPublish(
        CanonicalKnowledgeDocument(1, listOf(KnowledgeBlock.Paragraph(text))),
    )

    private fun insertPublisher(): UUID {
        val id = UUID.randomUUID()
        val email = "consent-projection-$id@example.test"
        val now = Timestamp.from(Instant.parse("2026-08-26T01:00:00Z"))
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, '합성 테스트 관리자', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email,
            email,
            BCryptPasswordEncoder(4).encode("Consent projection password 42!"),
            now,
            now,
        )
        return id
    }

    private fun clearState() {
        jdbc.execute(
            """
            truncate table customer_registration_intent_consents, customer_registration_intents,
                customer_consent_acceptances, customer_consent_policy_versions,
                customer_consent_policies, admin_security_audit_events, staff_login_throttles,
                staff_accounts restart identity cascade
            """.trimIndent(),
        )
    }

    private fun assertExactProperties(node: JsonNode, vararg expected: String) {
        assertThat(node.properties().asSequence().map(Map.Entry<String, JsonNode>::key).toSet())
            .isEqualTo(expected.toSet())
    }
}
