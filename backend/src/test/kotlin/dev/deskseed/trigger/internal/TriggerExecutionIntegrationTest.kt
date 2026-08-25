package dev.deskseed.trigger.internal

import dev.deskseed.webhook.internal.WebhookEventOutboxWorker
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@AutoConfigureMockMvc
@dev.deskseed.testsupport.category.IntegrationTest
class TriggerExecutionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var worker: TriggerEvaluationWorker
    @Autowired private lateinit var webhookOutboxWorker: WebhookEventOutboxWorker

    @BeforeEach
    fun clearData() {
        jdbc.execute(
            """
            truncate table
                webhook_delivery_attempts,
                webhook_deliveries,
                webhook_subscriptions,
                webhook_endpoint_secrets,
                webhook_endpoints,
                domain_event_outbox,
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
    fun `worker evaluates ordered rules against evolving state and creates one audit per matched rule plus webhook intent`() {
        val admin = browser()
        val firstGroup = activeGroup("1차 긴급 그룹")
        val secondGroup = activeGroup("2차 후속 그룹")
        val firstTrigger = createAndActivate(admin, urgentUnassignedTrigger("1차 라우팅", 10, firstGroup, true))
        val secondTrigger = createAndActivate(admin, groupPresentTrigger("2차 라우팅", 20, secondGroup))
        val ticketNumber = createUrgentTicket(admin, "ordered-trigger@example.com", "ordered trigger")

        assertThat(worker.runOnce("trigger-test-worker")).isTrue()

        assertThat(jdbc.queryForMap("select group_id, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("group_id", secondGroup)
            .containsEntry("version", 2L)
        assertThat(jdbc.queryForList(
            """
            select audit.actor_type, event.event_type
              from ticket_audits audit
              join ticket_audit_events event on event.audit_id = audit.id
              join tickets ticket on ticket.id = audit.ticket_id
             where ticket.ticket_number = ? and audit.actor_type = 'TRIGGER'
             order by audit.created_at, audit.id, event.event_order
            """.trimIndent(),
            ticketNumber,
        ).map { it["actor_type"] to it["event_type"] }).containsExactly(
            "TRIGGER" to "TRIGGER_APPLIED",
            "TRIGGER" to "GROUP_CHANGED",
            "TRIGGER" to "TRIGGER_APPLIED",
            "TRIGGER" to "GROUP_CHANGED",
        )
        assertThat(jdbc.queryForList(
            "select trigger_id, trigger_version, position, outcome, ticket_audit_id from trigger_executions order by position",
        )).satisfiesExactly(
            { row ->
                assertThat(row).containsEntry("trigger_id", firstTrigger).containsEntry("trigger_version", 1)
                    .containsEntry("position", 10).containsEntry("outcome", "MATCHED")
                assertThat(row["ticket_audit_id"]).isNotNull()
            },
            { row ->
                assertThat(row).containsEntry("trigger_id", secondTrigger).containsEntry("trigger_version", 1)
                    .containsEntry("position", 20).containsEntry("outcome", "MATCHED")
                assertThat(row["ticket_audit_id"]).isNotNull()
            },
        )
        assertThat(jdbc.queryForObject(
            "select count(*) from domain_event_outbox where event_type = 'ticket.trigger.executed' and status = 'PENDING'",
            Long::class.java,
        )).isEqualTo(1)
        assertThat(jdbc.queryForMap(
            "select status, attempt_count, last_error_code from trigger_evaluation_jobs where ticket_number = ?",
            ticketNumber,
        )).containsEntry("status", "SUCCEEDED").containsEntry("attempt_count", 1).containsEntry("last_error_code", null)
        assertThat(jdbc.queryForObject("select count(*) from webhook_deliveries", Long::class.java)).isZero()

        assertThat(worker.runOnce("trigger-test-worker")).isFalse()
        assertThat(jdbc.queryForObject(
            """
            select count(*) from ticket_audits audit join tickets ticket on ticket.id = audit.ticket_id
             where ticket.ticket_number = ? and audit.actor_type = 'TRIGGER'
            """.trimIndent(),
            Long::class.java, ticketNumber,
        )).isEqualTo(2)
    }

    @Test
    fun `trigger execution for an internal work item creates no external webhook delivery`() {
        val admin = browser()
        val targetGroup = activeGroup("내부 작업 트리거 그룹")
        createAndActivate(admin, urgentUnassignedTrigger("내부 작업 라우팅", 10, targetGroup, true))
        subscribeWebhook("ticket.trigger.executed")
        val ticketNumber = createUrgentTicket(admin, "internal-work-item-trigger@example.com", "internal work item")
        jdbc.update("update tickets set kind = 'INTERNAL_WORK_ITEM' where ticket_number = ?", ticketNumber)

        assertThat(worker.runOnce("internal-work-item-trigger-worker")).isTrue()
        while (webhookOutboxWorker.runOnce("internal-work-item-trigger-materializer")) {
            // Drain this test's outbox rows to exercise the external fan-out boundary.
        }

        assertThat(jdbc.queryForList(
            "select visibility from domain_event_outbox where event_type = 'ticket.trigger.executed'",
            String::class.java,
        )).containsExactly("INTERNAL")
        assertThat(jdbc.queryForObject("select count(*) from webhook_deliveries", Long::class.java)).isZero()
    }

    @Test
    fun `invariant failure retries then dead letters without partial ticket mutation`() {
        val admin = browser()
        val targetGroup = activeGroup("실패 대상 그룹")
        createAndActivate(admin, urgentUnassignedTrigger("실패 라우팅", 10, targetGroup, true))
        jdbc.update("update support_groups set status = 'DISABLED', updated_at = now(), version = version + 1 where id = ?", targetGroup)
        val ticketNumber = createUrgentTicket(admin, "failed-trigger@example.com", "failed trigger")

        repeat(5) { attempt ->
            if (attempt > 0) {
                jdbc.update(
                    "update trigger_evaluation_jobs set available_at = now() where ticket_number = ? and status = 'RETRY_SCHEDULED'",
                    ticketNumber,
                )
            }
            assertThat(worker.runOnce("trigger-failure-worker")).isTrue()
        }

        assertThat(jdbc.queryForMap("select group_id, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("group_id", null)
            .containsEntry("version", 0L)
        assertThat(jdbc.queryForMap(
            "select status, attempt_count, last_error_code from trigger_evaluation_jobs where ticket_number = ?",
            ticketNumber,
        )).containsEntry("status", "DEAD_LETTERED").containsEntry("attempt_count", 5)
        assertThat(jdbc.queryForObject(
            "select count(*) from trigger_executions",
            Long::class.java,
        )).isZero()
        assertThat(jdbc.queryForObject(
            """
            select count(*) from ticket_audits audit join tickets ticket on ticket.id = audit.ticket_id
             where ticket.ticket_number = ? and audit.actor_type = 'TRIGGER'
            """.trimIndent(),
            Long::class.java, ticketNumber,
        )).isZero()
        assertThat(jdbc.queryForObject(
            "select count(*) from domain_event_outbox where event_type = 'ticket.trigger.executed'",
            Long::class.java,
        )).isZero()
    }

    @Test
    fun `duplicate rule version snapshot is loop blocked without action or webhook side effects`() {
        val admin = browser()
        val targetGroup = activeGroup("loop 방지 그룹")
        createAndActivate(admin, urgentUnassignedTrigger("loop 방지 라우팅", 10, targetGroup, true))
        val ticketNumber = createUrgentTicket(admin, "loop-trigger@example.com", "loop trigger")
        jdbc.update(
            "update trigger_evaluation_jobs set trigger_versions_json = trigger_versions_json || trigger_versions_json where ticket_number = ?",
            ticketNumber,
        )

        assertThat(worker.runOnce("trigger-loop-worker")).isTrue()

        assertThat(jdbc.queryForMap("select group_id, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("group_id", null)
            .containsEntry("version", 0L)
        assertThat(jdbc.queryForMap("select outcome, error_code from trigger_executions"))
            .containsEntry("outcome", "LOOP_BLOCKED")
            .containsEntry("error_code", "DUPLICATE_RULE_VERSION")
        assertThat(jdbc.queryForObject(
            "select count(*) from domain_event_outbox where event_type = 'ticket.trigger.executed'",
            Long::class.java,
        )).isZero()
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_audits where actor_type = 'TRIGGER'",
            Long::class.java,
        )).isZero()
    }

    private fun createAndActivate(browser: Browser, definition: String): UUID {
        val created = mockMvc.perform(
            post("/api/v1/admin/triggers")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON).content(definition),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val id = UUID.fromString(stringField(created, "id"))
        mockMvc.perform(
            put("/api/v1/admin/triggers/{triggerId}/activation", id)
                .session(browser.session).csrf(browser).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)
        return id
    }

    private fun createUrgentTicket(browser: Browser, email: String, subject: String): Long {
        val json = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(browser.session).csrf(browser).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester":{"name":"트리거 고객","email":"$email"},
                      "subject":"$subject",
                      "firstComment":{"visibility":"PUBLIC","body":"긴급 문의입니다."},
                      "priority":"URGENT"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return Regex("\\\"ticketNumber\\\":(\\d+)").find(json)!!.groupValues[1].toLong()
    }

    private fun urgentUnassignedTrigger(name: String, position: Int, groupId: UUID, webhook: Boolean): String =
        triggerJson(
            name,
            position,
            """{"group":"ALL","field":"PRIORITY","operator":"IS","value":"URGENT"},{"group":"ALL","field":"GROUP","operator":"NOT_PRESENT"}""",
            """{"type":"SET_GROUP","groupId":"$groupId"}${if (webhook) ",{" + "\"type\":\"ENQUEUE_WEBHOOK\",\"eventType\":\"ticket.trigger.executed\"}" else ""}""",
        )

    private fun groupPresentTrigger(name: String, position: Int, groupId: UUID): String = triggerJson(
        name,
        position,
        """{"group":"ALL","field":"GROUP","operator":"PRESENT"}""",
        """{"type":"SET_GROUP","groupId":"$groupId"}""",
    )

    private fun triggerJson(name: String, position: Int, extraConditions: String, actions: String) =
        """
        {"name":"$name","position":$position,
         "conditions":[{"group":"ALL","field":"EVENT","operator":"IS","value":"TICKET_CREATED"},$extraConditions],
         "actions":[$actions]}
        """.trimIndent()

    private fun activeGroup(name: String): UUID = UUID.randomUUID().also { id ->
        jdbc.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, ?, 'ACTIVE', now(), now(), 0)",
            id, name,
        )
    }

    private fun subscribeWebhook(eventType: String) {
        val endpointId = UUID.randomUUID()
        val staffId = jdbc.queryForObject("select id from staff_accounts limit 1", UUID::class.java)!!
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into webhook_endpoints (
                id, name, url, enabled, target_class, allowed_hostnames_json, allowed_ports_json, allowed_cidrs_json,
                health_state, cooldown_until, consecutive_failures, last_succeeded_at, last_failed_at, created_by_staff_id,
                created_at, updated_at, deactivated_at, version
            ) values (?, ?, 'https://203.0.113.10/hook', true, 'PUBLIC', '[]', '[443]', '[]', 'CLOSED', null, 0, null, null,
                      ?, ?, ?, null, 0)
            """.trimIndent(),
            endpointId, "Trigger $eventType", staffId, now, now,
        )
        jdbc.update(
            """
            insert into webhook_subscriptions (endpoint_id, event_type, event_version, payload_policy, created_at)
            values (?, ?, 1, 'METADATA_ONLY', ?)
            """.trimIndent(),
            endpointId, eventType, now,
        )
    }

    private fun browser(): Browser {
        val email = "trigger-admin-${UUID.randomUUID()}@example.com"
        val password = "Trigger password 42!"
        val staffId = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, '트리거 관리자', 'ADMIN', 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            staffId, email.lowercase(), email, BCryptPasswordEncoder(4).encode(password),
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
