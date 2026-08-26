package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AdminCustomerConsentIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    @AfterEach
    fun clearState() {
        jdbc.execute(
            """
            truncate table customer_consent_acceptances, customer_consent_policy_versions,
                customer_consent_policies, admin_security_audit_events, staff_login_throttles,
                staff_accounts restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `admin creates edits publishes lists reads and archives with strong etags and no store`() {
        val admin = browser("ADMIN")
        val created = mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies")
                .staff(admin).csrf(admin).header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(createBody()),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.draft.document.blocks[0].text").value("합성 정책 본문"))
            .andReturn().response.contentAsString
        val policyId = UUID.fromString(text(created, "id"))

        val updated = mockMvc.perform(
            put("/api/v1/admin/customer-consent-policies/{policyId}", policyId)
                .staff(admin).csrf(admin).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(updateBody()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.draft.draftVersion").value(2))
            .andReturn().response.contentAsString
        val draftUpdatedAt = text(objectMapper.readTree(updated).get("draft").toString(), "updatedAt")

        mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies/{policyId}/publish", policyId)
                .staff(admin).csrf(admin),
        )
            .andExpect(status().isPreconditionFailed)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.currentVersion").value(1))

        val published = mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies/{policyId}/publish", policyId)
                .staff(admin).csrf(admin).header("If-Match", "\"1\""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.lifecycle").value("PUBLISHED"))
            .andExpect(jsonPath("$.publishedVersion.version").value(1))
            .andExpect(jsonPath("$.versions.length()").value(1))
            .andReturn().response.contentAsString
        val publishedNode = objectMapper.readTree(published)
        assertThat(publishedNode.get("draft").get("updatedAt").asString()).isEqualTo(draftUpdatedAt)
        assertThat(publishedNode.get("publishedVersion").get("effectiveAt").asString())
            .isEqualTo(publishedNode.get("publishedVersion").get("publishedAt").asString())

        mockMvc.perform(
            get("/api/v1/admin/customer-consent-policies")
                .staff(admin).queryParam("context", "REGISTRATION").queryParam("lifecycle", "PUBLISHED"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.items[0].id").value(policyId.toString()))
            .andExpect(jsonPath("$.items[0].draft").doesNotExist())
            .andExpect(jsonPath("$.items[0].document").doesNotExist())

        mockMvc.perform(get("/api/v1/admin/customer-consent-policies/{policyId}", policyId).staff(admin))
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.versions[0].policyKey").value("test-terms"))
            .andExpect(jsonPath("$.versions[0].plainText").value("개정 정책 본문"))

        mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies/{policyId}/archive", policyId)
                .staff(admin).csrf(admin).header("If-Match", "\"2\""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.lifecycle").value("ARCHIVED"))
            .andExpect(jsonPath("$.versions[0].version").value(1))

        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where target_id = ?",
            Long::class.java,
            policyId,
        )).isEqualTo(4)
    }

    @Test
    fun `session role expected actor csrf and create precondition fail closed`() {
        val actor = UUID.randomUUID()
        mockMvc.perform(
            get("/api/v1/admin/customer-consent-policies")
                .header("X-Deskseed-Expected-Staff-Id", actor),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/staff-session-required"))

        val agent = browser("AGENT")
        mockMvc.perform(get("/api/v1/admin/customer-consent-policies").staff(agent))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-forbidden"))

        val admin = browser("ADMIN")
        mockMvc.perform(get("/api/v1/admin/customer-consent-policies").session(admin.session))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-request-invalid"))
        mockMvc.perform(
            get("/api/v1/admin/customer-consent-policies").session(admin.session)
                .header("X-Deskseed-Expected-Staff-Id", "not-a-uuid"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/invalid-staff-session-actor"))
        mockMvc.perform(
            get("/api/v1/admin/customer-consent-policies").session(admin.session)
                .header("X-Deskseed-Expected-Staff-Id", UUID.randomUUID()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/staff-session-actor-mismatch"))

        mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies")
                .staff(admin).header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(createBody()),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/customer-consent-forbidden"))
        mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies")
                .staff(admin).csrf(admin).contentType(MediaType.APPLICATION_JSON).content(createBody()),
        )
            .andExpect(status().isPreconditionFailed)
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.currentVersion").value(0))

        val oversized = createBody() + " ".repeat(262_145)
        mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies")
                .staff(admin).csrf(admin).header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(oversized),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Cache-Control", "no-store"))
        assertThat(jdbc.queryForObject("select count(*) from customer_consent_policies", Long::class.java)).isZero()
    }

    @Test
    fun `stale and audit unavailable updates leave the draft unchanged`() {
        val admin = browser("ADMIN")
        val created = mockMvc.perform(
            post("/api/v1/admin/customer-consent-policies")
                .staff(admin).csrf(admin).header("If-None-Match", "*")
                .contentType(MediaType.APPLICATION_JSON).content(createBody()),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val policyId = UUID.fromString(text(created, "id"))

        mockMvc.perform(
            put("/api/v1/admin/customer-consent-policies/{policyId}", policyId)
                .staff(admin).csrf(admin).header("If-Match", "\"9\"")
                .contentType(MediaType.APPLICATION_JSON).content(updateBody()),
        )
            .andExpect(status().isPreconditionFailed)
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.currentVersion").value(0))
        assertThat(auditCount(policyId)).isEqualTo(1)

        installFailingAuditTrigger()
        try {
            mockMvc.perform(
                put("/api/v1/admin/customer-consent-policies/{policyId}", policyId)
                    .staff(admin).csrf(admin).header("If-Match", "\"0\"")
                    .contentType(MediaType.APPLICATION_JSON).content(updateBody()),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/customer-consent-unavailable"))
        } finally {
            jdbc.execute("drop trigger if exists fail_customer_consent_http_audit on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_customer_consent_http_audit()")
        }
        mockMvc.perform(get("/api/v1/admin/customer-consent-policies/{policyId}", policyId).staff(admin))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.aggregateVersion").value(0))
            .andExpect(jsonPath("$.draft.title").value("합성 테스트 이용 조건"))
        assertThat(auditCount(policyId)).isEqualTo(1)
    }

    @Test
    fun `list and detail read failures return the stable unavailable problem`() {
        val admin = browser("ADMIN")
        jdbc.execute("alter table customer_consent_policies rename to customer_consent_policies_unavailable")
        try {
            val responses = listOf(
                mockMvc.perform(get("/api/v1/admin/customer-consent-policies").staff(admin)),
                mockMvc.perform(
                    get("/api/v1/admin/customer-consent-policies/{policyId}", UUID.randomUUID()).staff(admin),
                ),
            )
            responses.forEach { response ->
                val body = response
                    .andExpect(status().isServiceUnavailable)
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.type").value("/problems/customer-consent-unavailable"))
                    .andReturn().response.contentAsString
                assertThat(body).doesNotContain("customer_consent_policies")
            }
        } finally {
            jdbc.execute("alter table customer_consent_policies_unavailable rename to customer_consent_policies")
        }
    }

    private fun createBody() =
        """{"policyKey":"test-terms","context":"REGISTRATION","title":"합성 테스트 이용 조건","document":{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"합성 정책 본문"}]},"required":true,"displayOrder":10}"""

    private fun updateBody() =
        """{"title":"합성 테스트 이용 조건 개정안","document":{"schemaVersion":1,"blocks":[{"type":"paragraph","text":"개정 정책 본문"}]},"required":true,"displayOrder":10}"""

    private fun browser(role: String): Browser {
        val id = UUID.randomUUID()
        val email = "consent-${role.lowercase()}-$id@example.test"
        val password = "Consent password 42!"
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id, email, email, "Consent $role", role, BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
        )
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = text(csrf.response.contentAsString, "token")
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(id, login.request.session as MockHttpSession, token)
    }

    private fun installFailingAuditTrigger() {
        jdbc.execute(
            """
            create or replace function fail_customer_consent_http_audit() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'forced customer consent HTTP audit failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_customer_consent_http_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_customer_consent_http_audit()",
        )
    }

    private fun auditCount(policyId: UUID): Long = jdbc.queryForObject(
        "select count(*) from admin_security_audit_events where target_id = ?",
        Long::class.java,
        policyId,
    ) ?: 0

    private fun MockHttpServletRequestBuilder.staff(browser: Browser) =
        session(browser.session).header("X-Deskseed-Expected-Staff-Id", browser.id)

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)
    private fun text(json: String, field: String): String = objectMapper.readTree(json).get(field).asString()
    private data class Browser(val id: UUID, val session: MockHttpSession, val csrfToken: String)
}
