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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
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
class TriggerDefinitionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearData() {
        jdbc.execute(
            """
            truncate table
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
    fun `admin versions activates reorders and dry runs an urgent unassigned trigger without side effects`() {
        val admin = browser("ADMIN")
        val groupId = activeGroup("긴급 문의 그룹")
        val ticketJson = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester":{"name":"긴급 고객","email":"urgent-trigger@example.com"},
                      "subject":"서비스 중단",
                      "firstComment":{"visibility":"PUBLIC","body":"지금 서비스를 사용할 수 없습니다."},
                      "priority":"URGENT"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(ticketJson, "ticketNumber")
        val triggerBody = triggerJson("긴급 미배정 라우팅", 10, groupId)
        val createdJson = mockMvc.perform(
            post("/api/v1/admin/triggers")
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON).content(triggerBody),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.activeVersion").doesNotExist())
            .andExpect(jsonPath("$.conditions[2].operator").value("NOT_PRESENT"))
            .andReturn().response.contentAsString
        val triggerId = UUID.fromString(stringField(createdJson, "id"))

        mockMvc.perform(
            put("/api/v1/admin/triggers/{triggerId}/activation", triggerId)
                .session(admin.session).csrf(admin).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.activeVersion").value(1))

        mockMvc.perform(
            post("/api/v1/admin/triggers/{triggerId}/versions/{version}/dry-run", triggerId, 1)
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content("""{"ticketNumber":$ticketNumber,"eventType":"TICKET_CREATED"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.matchedConditions.length()").value(3))
            .andExpect(jsonPath("$.proposedActions[0]").value("SET_GROUP"))
            .andExpect(jsonPath("$.invariantFailures.length()").value(0))

        assertThat(jdbc.queryForMap("select group_id, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("group_id", null)
            .containsEntry("version", 0L)
        assertThat(jdbc.queryForObject("select count(*) from trigger_evaluation_jobs", Long::class.java)).isZero()
        assertThat(jdbc.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java, triggerId,
        )).containsExactly("TRIGGER_CREATED", "TRIGGER_ACTIVATED")
    }

    @Test
    fun `ticket creation snapshots active ordered versions into a durable job in the root transaction`() {
        val admin = browser("ADMIN")
        val groupId = activeGroup("durable trigger 그룹")
        val createdTrigger = mockMvc.perform(
            post("/api/v1/admin/triggers")
                .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                .content(triggerJson("durable 긴급 라우팅", 10, groupId)),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val triggerId = UUID.fromString(stringField(createdTrigger, "id"))
        mockMvc.perform(
            put("/api/v1/admin/triggers/{triggerId}/activation", triggerId)
                .session(admin.session).csrf(admin).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)

        val ticketJson = createUrgentTicket(admin, "durable-job@example.com", "durable job 검증", status().isCreated)
        val ticketNumber = longField(ticketJson, "ticketNumber")
        val job = jdbc.queryForMap(
            """
            select ticket_number, root_ticket_audit_id, root_correlation_id, event_type,
                   trigger_versions_json::text as versions, status, attempt_count
              from trigger_evaluation_jobs
             where ticket_number = ?
            """.trimIndent(),
            ticketNumber,
        )
        assertThat(job)
            .containsEntry("ticket_number", ticketNumber)
            .containsEntry("event_type", "TICKET_CREATED")
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 0)
        assertThat(job["root_ticket_audit_id"]).isNotNull()
        assertThat(job["root_correlation_id"]).isNotNull()
        assertThat(job["versions"].toString()).contains(triggerId.toString(), "\"triggerVersion\": 1", "\"position\": 10")

        mockMvc.perform(
            delete("/api/v1/admin/triggers/{triggerId}/activation", triggerId)
                .session(admin.session).csrf(admin).header("If-Match", "\"2\""),
        ).andExpect(status().isOk)
        assertThat(jdbc.queryForObject(
            "select trigger_versions_json::text from trigger_evaluation_jobs where ticket_number = ?",
            String::class.java, ticketNumber,
        )).contains(triggerId.toString(), "\"triggerVersion\": 1")

        mockMvc.perform(
            put("/api/v1/admin/triggers/{triggerId}/activation", triggerId)
                .session(admin.session).csrf(admin).header("If-Match", "\"3\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)
        jdbc.execute(
            """
            create or replace function fail_trigger_job_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'forced durable job failure'; end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_trigger_job_insert before insert on trigger_evaluation_jobs for each row execute function fail_trigger_job_insert()",
        )
        try {
            createUrgentTicket(admin, "durable-failure@example.com", "rollback ticket", status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_trigger_job_insert on trigger_evaluation_jobs")
            jdbc.execute("drop function if exists fail_trigger_job_insert()")
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from tickets where subject = 'rollback ticket'",
            Long::class.java,
        )).isZero()
    }

    @Test
    fun `agent cannot manage triggers and required audit failure rolls back creation`() {
        val agent = browser("AGENT")
        val groupId = activeGroup("권한 검증 그룹")
        mockMvc.perform(
            post("/api/v1/admin/triggers")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(triggerJson("권한 없는 규칙", 20, groupId)),
        ).andExpect(status().isForbidden)

        val admin = browser("ADMIN")
        jdbc.execute(
            """
            create or replace function fail_trigger_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'TRIGGER_CREATED' then raise exception 'forced trigger audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_trigger_audit_insert before insert on admin_security_audit_events for each row execute function fail_trigger_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/triggers")
                    .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(triggerJson("감사 실패 규칙", 30, groupId)),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_trigger_audit_insert on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_trigger_audit_insert()")
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from trigger_definitions where normalized_name = '감사 실패 규칙'",
            Long::class.java,
        )).isZero()
    }

    private fun activeGroup(name: String): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, ?, 'ACTIVE', now(), now(), 0)",
            id, name,
        )
    }

    private fun createUrgentTicket(
        browser: Browser,
        requesterEmail: String,
        subject: String,
        expectedStatus: org.springframework.test.web.servlet.ResultMatcher,
    ): String = mockMvc.perform(
        post("/api/v1/agent/tickets")
            .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "requester":{"name":"긴급 고객","email":"$requesterEmail"},
                  "subject":"$subject",
                  "firstComment":{"visibility":"PUBLIC","body":"긴급 문의입니다."},
                  "priority":"URGENT"
                }
                """.trimIndent(),
            ),
    ).andExpect(expectedStatus).andReturn().response.contentAsString

    private fun triggerJson(name: String, position: Int, groupId: UUID) =
        """
        {
          "name":"$name",
          "position":$position,
          "conditions":[
            {"group":"ALL","field":"EVENT","operator":"IS","value":"TICKET_CREATED"},
            {"group":"ALL","field":"PRIORITY","operator":"IS","value":"URGENT"},
            {"group":"ALL","field":"GROUP","operator":"NOT_PRESENT"}
          ],
          "actions":[
            {"type":"SET_GROUP","groupId":"$groupId"},
            {"type":"ENQUEUE_WEBHOOK","eventType":"ticket.trigger.executed"}
          ]
        }
        """.trimIndent()

    private fun browser(role: String): Browser {
        val email = "trigger-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Trigger password 42!"
        val staffId = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            staffId, email.lowercase(), email, if (role == "ADMIN") "트리거 관리자" else "트리거 상담사", role,
            BCryptPasswordEncoder(4).encode(password), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
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
    private fun longField(json: String, field: String): Long = Regex("\\\"$field\\\":(\\d+)").find(json)!!.groupValues[1].toLong()
    private data class Browser(val session: MockHttpSession, val csrfToken: String)
}
