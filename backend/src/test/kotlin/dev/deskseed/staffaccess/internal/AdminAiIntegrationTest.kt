package dev.deskseed.staffaccess.internal

import dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@DeskseedSpringIntegrationTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AdminAiIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var databaseCleaner: dev.deskseed.testsupport.integration.StaffTicketTestDatabaseCleaner

    @BeforeEach
    fun clearState() = databaseCleaner.resetMutableStaffTicketState()

    @Test
    fun `settings default off and update is versioned audited and secret-free`() {
        val admin = insertStaff("ai-admin@example.com", "ADMIN")
        val agent = insertStaff("allowed-agent@example.com", "AGENT")
        val session = login("ai-admin@example.com")

        mockMvc.perform(get("/api/v1/admin/ai/settings").session(session))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.enabled").value(false))
            .andExpect(jsonPath("$.replyRewriteEnabled").value(false))
            .andExpect(jsonPath("$.replyRoutingMode").value("STANDARD_ONLY"))
            .andExpect(jsonPath("$.replyRoutingCohorts").isEmpty)
            .andExpect(jsonPath("$.replyRoutingRolloutPercent").value(0))
            .andExpect(jsonPath("$.replyRoutingEvaluationApprovalVersion").doesNotExist())
            .andExpect(jsonPath("$.version").value(0))

        val update = """
            {
              "enabled": true,
              "summaryEnabled": true,
              "triageEnabled": true,
              "replyDraftEnabled": false,
              "replyRewriteEnabled": true,
              "fastModelAlias": "openai/gpt-5.6-luna",
              "standardModelAlias": "openai/gpt-5.6-terra",
              "replyRoutingMode": "STANDARD_ONLY",
              "replyRoutingCohorts": [],
              "replyRoutingRolloutPercent": 0,
              "replyRoutingEvaluationApprovalVersion": null,
              "allowedStaffIds": ["$agent"],
              "expectedVersion": 0
            }
        """.trimIndent()
        mockMvc.perform(
            put("/api/v1/admin/ai/settings")
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Deskseed-Expected-Staff-Id", admin.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(update),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.replyRewriteEnabled").value(true))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.allowedStaffIds[0]").value(agent.toString()))
            .andExpect(jsonPath("$.secret").doesNotExist())

        mockMvc.perform(
            put("/api/v1/admin/ai/settings")
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Deskseed-Expected-Staff-Id", admin.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(update),
        ).andExpect(status().isConflict)

        assertThat(count("select count(*) from admin_security_audit_events where event_type = 'AI_SETTINGS_UPDATED'"))
            .isEqualTo(1)
        assertThat(count("select count(*) from ai_feature_staff_allowlist where staff_id = '$agent'"))
            .isEqualTo(1)
    }

    @Test
    fun `evaluated reply routing requires exact cohort rollout and approval version`() {
        val admin = insertStaff("ai-routing-admin@example.com", "ADMIN")
        val agent = insertStaff("ai-routing-agent@example.com", "AGENT")
        val session = login("ai-routing-admin@example.com")
        val active = """
            {
              "enabled": true,
              "summaryEnabled": true,
              "triageEnabled": true,
              "replyDraftEnabled": true,
              "replyRewriteEnabled": true,
              "fastModelAlias": "openai/gpt-5.6-luna",
              "standardModelAlias": "openai/gpt-5.6-terra",
              "replyRoutingMode": "EVALUATED_COHORT",
              "replyRoutingCohorts": ["reply-single-public-article-short-v1"],
              "replyRoutingRolloutPercent": 10,
              "replyRoutingEvaluationApprovalVersion": "eval-2026-09-19-v1",
              "allowedStaffIds": ["$agent"],
              "expectedVersion": 0
            }
        """.trimIndent()

        mockMvc.perform(
            put("/api/v1/admin/ai/settings")
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Deskseed-Expected-Staff-Id", admin.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(active),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.replyRoutingMode").value("EVALUATED_COHORT"))
            .andExpect(
                jsonPath("$.replyRoutingCohorts[0]")
                    .value("reply-single-public-article-short-v1"),
            )
            .andExpect(jsonPath("$.replyRoutingRolloutPercent").value(10))
            .andExpect(
                jsonPath("$.replyRoutingEvaluationApprovalVersion")
                    .value("eval-2026-09-19-v1"),
            )

        val invalid = active
            .replace("\"replyRoutingRolloutPercent\": 10", "\"replyRoutingRolloutPercent\": 25")
            .replace("\"expectedVersion\": 0", "\"expectedVersion\": 1")
        mockMvc.perform(
            put("/api/v1/admin/ai/settings")
                .session(session)
                .header("X-CSRF-TOKEN", csrf(session))
                .header("X-Deskseed-Expected-Staff-Id", admin.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid),
        ).andExpect(status().isBadRequest)

        assertThat(jdbcTemplate.queryForObject("select version from ai_settings", Long::class.java))
            .isEqualTo(1)
        assertThat(count("select count(*) from ai_reply_routing_cohorts"))
            .isEqualTo(1)
    }

    @Test
    fun `reindex operation is idempotent and status remains content-free`() {
        val admin = insertStaff("ai-admin-reindex@example.com", "ADMIN")
        val session = login("ai-admin-reindex@example.com")
        val operationId = UUID.randomUUID()
        repeat(2) { attempt ->
            mockMvc.perform(
                post("/api/v1/admin/ai/kb/reindex")
                    .session(session)
                    .header("X-CSRF-TOKEN", csrf(session))
                    .header("X-Deskseed-Expected-Staff-Id", admin.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"operationId":"$operationId"}"""),
            )
                .andExpect(status().isAccepted)
                .andExpect(jsonPath("$.replayed").value(attempt == 1))
        }
        assertThat(count("select count(*) from ai_admin_operations where operation_id = '$operationId'"))
            .isEqualTo(1)
        assertThat(count("select count(*) from admin_security_audit_events where event_type = 'AI_KB_REINDEX_REQUESTED'"))
            .isEqualTo(1)

        val body = mockMvc.perform(get("/api/v1/admin/ai/status").session(session))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.settingsVersion").value(0))
            .andReturn().response.contentAsString
        assertThat(body.lowercase()).doesNotContain("password", "authorization", "secret", "token")
    }

    private fun insertStaff(email: String, role: String): UUID = UUID.randomUUID().also { id ->
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, 'AI 관리자', ?, 'ACTIVE', ?, now(), now(), 0)
            """.trimIndent(),
            id,
            email,
            email,
            role,
            BCryptPasswordEncoder(4).encode(PASSWORD),
        )
    }

    private fun login(email: String): MockHttpSession {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        return mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent).andReturn().request.session as MockHttpSession
    }

    private fun csrf(session: MockHttpSession): String = mockMvc.perform(
        get("/api/v1/agent/csrf").session(session),
    ).andExpect(status().isOk).andReturn().response.contentAsString.let { body ->
        Regex("\"token\":\"([^\"]+)\"").find(body)!!.groupValues[1]
    }

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private companion object {
        const val PASSWORD = "Admin password 42"
    }
}
