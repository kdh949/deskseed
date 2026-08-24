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

    @Test
    fun `agent cannot create versions or activate automations`() {
        val admin = browser()
        val created = mockMvc.perform(
            post("/api/v1/admin/automations")
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"권한 검증 자동화","position":10,"solvedAgeMinutes":60,"actionType":"CLOSE_TICKET"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val automationId = UUID.fromString(stringField(created, "id"))
        val agent = browser("AGENT")

        mockMvc.perform(
            post("/api/v1/admin/automations")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"권한 없는 자동화","position":20,"solvedAgeMinutes":60,"actionType":"CLOSE_TICKET"}"""),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            post("/api/v1/admin/automations/{automationId}/versions", automationId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"권한 없는 버전","solvedAgeMinutes":120,"actionType":"CLOSE_TICKET"}"""),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/v1/admin/automations/{automationId}/activation", automationId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isForbidden)

        assertThat(jdbc.queryForMap(
            "select current_version, active_version from automation_definitions where id = ?",
            automationId,
        )).containsEntry("current_version", 1).containsEntry("active_version", null)
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_versions where automation_id = ?",
            Long::class.java,
            automationId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_activations where automation_id = ?",
            Long::class.java,
            automationId,
        )).isZero()
    }

    @Test
    fun `required audit failures roll back automation definition version and activation`() {
        val admin = browser()
        installAuditFailure()
        try {
            mockMvc.perform(
                post("/api/v1/admin/automations")
                    .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"감사 실패 생성","position":10,"solvedAgeMinutes":60,"actionType":"CLOSE_TICKET"}"""),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            removeAuditFailure()
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_definitions where normalized_name = '감사 실패 생성'",
            Long::class.java,
        )).isZero()

        val created = mockMvc.perform(
            post("/api/v1/admin/automations")
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"감사 원자성 자동화","position":20,"solvedAgeMinutes":60,"actionType":"CLOSE_TICKET"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val automationId = UUID.fromString(stringField(created, "id"))
        installAuditFailure()
        try {
            mockMvc.perform(
                post("/api/v1/admin/automations/{automationId}/versions", automationId)
                    .session(admin.session).csrf(admin).header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"감사 실패 버전","solvedAgeMinutes":120,"actionType":"CLOSE_TICKET"}"""),
            ).andExpect(status().isServiceUnavailable)
            mockMvc.perform(
                put("/api/v1/admin/automations/{automationId}/activation", automationId)
                    .session(admin.session).csrf(admin).header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            removeAuditFailure()
        }
        assertThat(jdbc.queryForMap(
            "select current_version, active_version from automation_definitions where id = ?",
            automationId,
        )).containsEntry("current_version", 1).containsEntry("active_version", null)
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_versions where automation_id = ?",
            Long::class.java,
            automationId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select count(*) from automation_activations where automation_id = ?",
            Long::class.java,
            automationId,
        )).isZero()
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

    private fun installAuditFailure() {
        jdbc.execute(
            """
            create or replace function fail_automation_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type like 'AUTOMATION_%' then raise exception 'forced automation audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_automation_audit_insert before insert on admin_security_audit_events for each row execute function fail_automation_audit_insert()",
        )
    }

    private fun removeAuditFailure() {
        jdbc.execute("drop trigger if exists fail_automation_audit_insert on admin_security_audit_events")
        jdbc.execute("drop function if exists fail_automation_audit_insert()")
    }

    private fun browser(role: String = "ADMIN"): Browser {
        val email = "automation-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Automation password 42!"
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id, email.lowercase(), email, "자동화 $role", role, BCryptPasswordEncoder(4).encode(password),
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
