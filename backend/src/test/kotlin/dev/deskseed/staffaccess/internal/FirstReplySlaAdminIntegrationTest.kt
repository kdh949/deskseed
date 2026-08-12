package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false", "deskseed.sla.breach-scanner.initial-delay=1d"])
@AutoConfigureMockMvc
@Testcontainers
class FirstReplySlaAdminIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearAudit() {
        jdbc.execute(
            """
            truncate table sla_policy_activations, sla_policy_pause_statuses,
                sla_policy_priority_targets, sla_policy_versions, sla_policies cascade
            """.trimIndent(),
        )
        jdbc.execute("truncate table admin_security_audit_events")
    }

    @Test
    fun `admin previews creates versions and activates with immutable history and audit`() {
        val browser = browser("ADMIN")
        val name = "API First Reply ${UUID.randomUUID()}"
        mockMvc.perform(
            post("/api/v1/admin/sla-policies/preview")
                .session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(previewJson(name)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.targetMinutes").value(240))
            .andExpect(jsonPath("$.dueAt").value("2026-08-17T04:00:00Z"))

        val response = mockMvc.perform(
            post("/api/v1/admin/sla-policies")
                .session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyJson(name, 240)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(content().string(containsString("\"activeVersion\":null")))
            .andExpect(jsonPath("$.active").value(false))
            .andReturn().response.contentAsString
        val policyId = UUID.fromString(stringField(response, "id"))

        mockMvc.perform(
            post("/api/v1/admin/sla-policies/{id}/versions", policyId)
                .session(browser.session).csrf(browser).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyJson(name, 180)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.targets.NORMAL").value(180))

        mockMvc.perform(
            post("/api/v1/admin/sla-policies/{id}/versions", policyId)
                .session(browser.session).csrf(browser).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyJson(name, 120)),
        ).andExpect(status().isPreconditionFailed)

        mockMvc.perform(
            put("/api/v1/admin/sla-policies/{id}/versions/2/activation", policyId)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.activeVersion").value(2))
            .andExpect(jsonPath("$.active").value(true))

        mockMvc.perform(get("/api/v1/admin/sla-policies/{id}/versions", policyId).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].version").value(2))
            .andExpect(jsonPath("$[0].activeVersion").value(2))
            .andExpect(jsonPath("$[0].targets.NORMAL").value(180))
            .andExpect(jsonPath("$[1].version").value(1))
            .andExpect(jsonPath("$[1].targets.NORMAL").value(240))

        assertThat(jdbc.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java,
            policyId,
        )).containsExactly("SLA_POLICY_CREATED", "SLA_POLICY_VERSION_CREATED", "SLA_POLICY_ACTIVATED")
    }

    @Test
    fun `preview orders an unsaved candidate with active policies`() {
        val browser = browser("ADMIN")
        val activeResponse = mockMvc.perform(
            post("/api/v1/admin/sla-policies")
                .session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(policyJson("Higher priority", 30, position = 1)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val activePolicyId = UUID.fromString(stringField(activeResponse, "id"))
        mockMvc.perform(
            put("/api/v1/admin/sla-policies/{id}/versions/1/activation", activePolicyId)
                .session(browser.session).csrf(browser).header("If-Match", "\"0\""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/admin/sla-policies/preview")
                .session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(previewJson("Lower priority candidate", position = 100)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.policyId").value(activePolicyId.toString()))
            .andExpect(jsonPath("$.targetMinutes").value(30))
    }

    @Test
    fun `policy activation rejects closed and expired exception only schedules`() {
        val browser = browser("ADMIN")
        listOf(false, true).forEach { expiredOpen ->
            val scheduleResponse = mockMvc.perform(
                post("/api/v1/admin/business-schedules")
                    .session(browser.session).csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(closedScheduleJson("Closed ${UUID.randomUUID()}", expiredOpen)),
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val scheduleId = UUID.fromString(stringField(scheduleResponse, "id"))
            mockMvc.perform(
                put("/api/v1/admin/business-schedules/{id}/versions/1/activation", scheduleId)
                    .session(browser.session).csrf(browser).header("If-Match", "\"0\""),
            ).andExpect(status().isOk)

            val policyResponse = mockMvc.perform(
                post("/api/v1/admin/sla-policies")
                    .session(browser.session).csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(policyJson("Closed schedule policy", 60, scheduleId = scheduleId)),
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val policyId = UUID.fromString(stringField(policyResponse, "id"))

            mockMvc.perform(
                put("/api/v1/admin/sla-policies/{id}/versions/1/activation", policyId)
                    .session(browser.session).csrf(browser).header("If-Match", "\"0\""),
            )
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scheduleId"))
                .andExpect(jsonPath("$.fieldErrors[0].code").value("RECURRING_SCHEDULE_CAPACITY_REQUIRED"))
        }
    }

    @Test
    fun `non-admin policy access is denied and denial is audited`() {
        val browser = browser("AGENT")
        mockMvc.perform(get("/api/v1/admin/sla-policies").session(browser.session))
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))

        assertThat(jdbc.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'ACCESS_DENIED'",
            Long::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `terminal statuses are rejected as pause configuration`() {
        val browser = browser("ADMIN")
        val invalid = policyJson("Invalid pause", 60)
            .replace("\"pauseStatuses\":[\"PENDING\"]", "\"pauseStatuses\":[\"SOLVED\"]")

        mockMvc.perform(
            post("/api/v1/admin/sla-policies")
                .session(browser.session).csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `audit persistence failure rolls back policy creation`() {
        val browser = browser("ADMIN")
        val name = "Rollback First Reply ${UUID.randomUUID()}"
        jdbc.execute(
            """
            create or replace function fail_sla_policy_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'SLA_POLICY_CREATED' then raise exception 'injected SLA audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_sla_policy_audit_insert before insert on admin_security_audit_events for each row execute function fail_sla_policy_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/sla-policies")
                    .session(browser.session).csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(policyJson(name, 240)),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/admin-audit-unavailable"))
        } finally {
            jdbc.execute("drop trigger if exists fail_sla_policy_audit_insert on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_sla_policy_audit_insert()")
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from sla_policy_versions where name = ?",
            Long::class.java,
            name,
        )).isZero()
    }

    private fun previewJson(name: String, position: Int = 10) =
        """{"candidate":${policyJson(name, 240, position)},"ticket":{"priority":"NORMAL","groupId":null,"channel":"WEB"},"startAt":"2026-08-14T09:30:00Z"}"""

    private fun policyJson(
        name: String,
        normalMinutes: Int,
        position: Int = 10,
        scheduleId: UUID = UUID.fromString("51000000-0000-0000-0000-000000000001"),
    ) =
        """{"name":"$name","position":$position,"scheduleId":"$scheduleId","conditions":{"groupId":null,"channel":"WEB"},"targets":{"LOW":480,"NORMAL":$normalMinutes,"HIGH":120,"URGENT":60},"pauseStatuses":["PENDING"]}"""

    private fun closedScheduleJson(name: String, expiredOpen: Boolean): String {
        val weekdays = listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY")
            .joinToString(",") { "{\"weekday\":\"$it\",\"enabled\":false,\"intervals\":[]}" }
        val exceptions = if (expiredOpen) {
            "[{\"date\":\"2020-01-01\",\"mode\":\"OPEN\",\"intervals\":[{\"start\":\"09:00\",\"end\":\"10:00\"}],\"label\":\"expired\"}]"
        } else {
            "[]"
        }
        return """{"name":"$name","timeZone":"Asia/Seoul","weekdays":[$weekdays],"exceptions":$exceptions}"""
    }

    private fun browser(role: String): Browser {
        val email = "sla-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "SLA password 42!"
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), email.lowercase(), email,
            if (role == "ADMIN") "SLA 관리자" else "상담사", role,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = stringField(csrf.response.contentAsString, "token")
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun stringField(json: String, field: String): String =
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

    companion object {
        @Container @ServiceConnection @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
