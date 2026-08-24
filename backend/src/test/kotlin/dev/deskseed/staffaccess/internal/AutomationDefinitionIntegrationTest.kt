package dev.deskseed.staffaccess.internal

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class AutomationDefinitionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearData() {
        jdbc.execute(
            """
            truncate table
                automation_executions,
                automation_candidates,
                automation_activations,
                automation_versions,
                automation_definitions,
                trigger_executions,
                trigger_evaluation_jobs,
                trigger_activations,
                trigger_actions,
                trigger_conditions,
                trigger_versions,
                trigger_definitions,
                access_audit_events,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                tickets,
                customers,
                group_memberships,
                support_groups,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `admin creates activates and dry runs a versioned solved close automation`() {
        val admin = browser()
        val created = mockMvc.perform(
            post("/api/v1/admin/automations")
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"해결 티켓 자동 종료","position":10,"solvedAgeMinutes":60,"actionType":"CLOSE_TICKET"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.activeVersion").doesNotExist())
            .andExpect(jsonPath("$.solvedAgeMinutes").value(60))
            .andReturn().response.contentAsString
        val automationId = UUID.fromString(stringField(created, "id"))
        mockMvc.perform(
            put("/api/v1/admin/automations/{automationId}/activation", automationId)
                .session(admin.session).csrf(admin).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk).andExpect(header().string("ETag", "\"2\""))

        val ticketNumber = createTicket(admin)
        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/commands", ticketNumber)
                .session(admin.session).csrf(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"expectedVersion":0,"changedFields":["status"],"status":"SOLVED"}"""),
        ).andExpect(status().isOk)
        jdbc.update("update tickets set solved_at = now() - interval '61 minutes' where ticket_number = ?", ticketNumber)

        mockMvc.perform(
            post("/api/v1/admin/automations/{automationId}/versions/{version}/dry-run", automationId, 1)
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"ticketNumber":$ticketNumber}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SOLVED"))
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.proposedAction").value("CLOSE_TICKET"))

        assertThat(jdbc.queryForMap("select status, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("status", "SOLVED")
            .containsEntry("version", 1L)
        assertThat(jdbc.queryForObject("select count(*) from automation_candidates", Long::class.java)).isZero()
        assertThat(jdbc.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java, automationId,
        )).containsExactly("AUTOMATION_CREATED", "AUTOMATION_ACTIVATED")
    }

    private fun createTicket(browser: Browser): Long {
        val json = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"requester":{"name":"자동화 고객","email":"automation@example.com"},
                     "subject":"자동화 대상","firstComment":{"visibility":"PUBLIC","body":"해결 후 종료해 주세요."},
                     "priority":"NORMAL"}
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Regex("\\\"ticketNumber\\\":(\\d+)").find(json)!!.groupValues[1].toLong()
    }

    private fun browser(): Browser {
        val email = "automation-admin-${UUID.randomUUID()}@example.com"
        val password = "Automation password 42!"
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, '자동화 관리자', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id, email.lowercase(), email, BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
        )
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = stringField(csrf.response.contentAsString, "token")
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)
    private fun stringField(json: String, field: String): String = Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]
    private data class Browser(val session: MockHttpSession, val csrfToken: String)
}
