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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
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
    @Autowired private lateinit var customerPortal: dev.deskseed.ticketing.CustomerTicketPortal
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
    fun `unmatched rules do not consume the action execution budget`() {
        val admin = browser()
        val targetGroup = activeGroup("미일치 액션 예산 그룹")
        repeat(67) { index ->
            createAndActivate(
                admin,
                triggerJson(
                    "미일치 액션 예산 ${index + 1}",
                    index + 1,
                    """{"group":"ALL","field":"PRIORITY","operator":"IS","value":"LOW"}""",
                    """{"type":"SET_GROUP","groupId":"$targetGroup"},{"type":"SET_PRIORITY","priority":"HIGH"},{"type":"ENQUEUE_WEBHOOK","eventType":"ticket.trigger.executed"}""",
                ),
            )
        }
        val ticketNumber = createUrgentTicket(admin, "unmatched-budget@example.com", "미일치 액션 예산")

        assertThat(worker.runOnce("unmatched-budget-worker")).isTrue()

        assertThat(jdbc.queryForMap(
            "select status, last_error_code from trigger_evaluation_jobs where ticket_number = ?",
            ticketNumber,
        )).containsEntry("status", "SUCCEEDED").containsEntry("last_error_code", null)
        assertThat(jdbc.queryForObject(
            "select count(*) from trigger_executions where outcome = 'NOT_MATCHED'",
            Long::class.java,
        )).isEqualTo(67L)
        assertThat(jdbc.queryForMap("select group_id, priority, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("group_id", null)
            .containsEntry("priority", "URGENT")
            .containsEntry("version", 0L)
    }

    @Test
    fun `matched rules enforce the action execution budget after two hundred actions`() {
        val admin = browser()
        val targetGroup = activeGroup("실행 액션 예산 그룹")
        val fiveActions = """{"type":"SET_GROUP","groupId":"$targetGroup"},{"type":"SET_PRIORITY","priority":"HIGH"},{"type":"SET_ASSIGNEE","assigneeId":null},{"type":"NOTIFY_UNASSIGNED_GROUP"},{"type":"ENQUEUE_WEBHOOK","eventType":"ticket.trigger.executed"}"""
        repeat(40) { index ->
            createAndActivate(
                admin,
                triggerJson(
                    "실행 액션 예산 ${index + 1}",
                    index + 1,
                    """{"group":"ALL","field":"PRIORITY","operator":"IS_NOT","value":"LOW"}""",
                    fiveActions,
                ),
            )
        }
        val boundaryTicketNumber = createUrgentTicket(admin, "matched-budget-200@example.com", "실행 액션 예산 200")

        assertThat(worker.runOnce("matched-budget-worker")).isTrue()
        assertThat(jdbc.queryForMap(
            "select status, last_error_code from trigger_evaluation_jobs where ticket_number = ?",
            boundaryTicketNumber,
        )).containsEntry("status", "SUCCEEDED").containsEntry("last_error_code", null)
        assertThat(jdbc.queryForObject(
            "select count(*) from trigger_executions execution join trigger_evaluation_jobs job on job.id = execution.job_id where job.ticket_number = ? and execution.outcome = 'MATCHED'",
            Long::class.java,
            boundaryTicketNumber,
        )).isEqualTo(40L)

        createAndActivate(
            admin,
            triggerJson(
                "실행 액션 예산 201",
                41,
                """{"group":"ALL","field":"PRIORITY","operator":"IS_NOT","value":"LOW"}""",
                """{"type":"SET_PRIORITY","priority":"HIGH"}""",
            ),
        )
        val overflowTicketNumber = createUrgentTicket(admin, "matched-budget-201@example.com", "실행 액션 예산 201")

        assertThat(worker.runOnce("matched-budget-worker")).isTrue()
        assertThat(jdbc.queryForMap(
            "select status, last_error_code from trigger_evaluation_jobs where ticket_number = ?",
            overflowTicketNumber,
        )).containsEntry("status", "RETRY_SCHEDULED").containsEntry("last_error_code", "ILLEGALARGUMENTEXCEPTION")
        assertThat(jdbc.queryForObject(
            "select count(*) from trigger_executions execution join trigger_evaluation_jobs job on job.id = execution.job_id where job.ticket_number = ?",
            Long::class.java,
            overflowTicketNumber,
        )).isZero()
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
                    "update trigger_evaluation_jobs set available_at = now() - interval '1 second' where ticket_number = ? and status = 'RETRY_SCHEDULED'",
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

    @Test
    fun `updated trigger combines tag form and final assignment atomically without recursive root or replay`() {
        val admin = browser()
        val ticketNumber = createUrgentTicket(admin, "updated-rules@example.com", "태그와 폼 규칙")
        val ticketId = jdbc.queryForObject("select id from tickets where ticket_number = ?", UUID::class.java, ticketNumber)!!
        val group = activeGroup("재답변 전담 그룹")
        val assignee = jdbc.queryForObject("select id from staff_accounts limit 1", UUID::class.java)!!
        jdbc.update("insert into group_memberships (id,group_id,staff_id,status,created_at,updated_at) values (?,?,?,'ACTIVE',now(),now())", UUID.randomUUID(), group, assignee)
        val tag = UUID.randomUUID()
        jdbc.update("insert into ticket_tag_definitions (id,normalized_value,label,created_at,updated_at) values (?,?,'환불',now(),now())", tag, "refund-$tag")
        jdbc.update("insert into ticket_tag_assignments values (?,?,now())", ticketId, tag)
        val form = UUID.randomUUID()
        jdbc.update("insert into ticket_forms (id,name,lifecycle,draft_definition_json,created_at,updated_at) values (?,'환불 접수','DRAFT','{}',now(),now())", form)
        jdbc.update("insert into ticket_form_versions (form_id,version,definition_json,published_by_staff_id,published_by_display,published_at) values (?,1,'{}',?,'관리자',now())", form, assignee)
        jdbc.update("insert into ticket_customer_form_bindings values (?,?,1,now())", ticketId, form)
        val rule = createAndActivate(admin, """{"name":"환불 재분류","position":1,"conditions":[{"group":"ALL","field":"EVENT","operator":"IS","value":"TICKET_UPDATED"},{"group":"ALL","field":"TAG","operator":"IS","value":"$tag"},{"group":"ALL","field":"FORM","operator":"IS","value":"$form"},{"group":"ALL","field":"ASSIGNEE","operator":"NOT_PRESENT"}],"actions":[{"type":"SET_ASSIGNEE","assigneeId":"$assignee"},{"type":"SET_PRIORITY","priority":"HIGH"},{"type":"SET_GROUP","groupId":"$group"}]}""")
        mockMvc.perform(post("/api/v1/admin/triggers/$rule/versions/1/dry-run").session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON).content("""{"ticketNumber":$ticketNumber,"eventType":"TICKET_UPDATED"}""")).andExpect(status().isOk).andExpect(jsonPath("$.matched").value(true)).andExpect(jsonPath("$.invariantFailures.length()").value(0))
        val command = """{"expectedVersion":0,"clientCommandId":"${UUID.randomUUID()}","changedFields":["priority"],"priority":"NORMAL"}"""
        fun update() = mockMvc.perform(post("/api/v1/agent/tickets/$ticketNumber/commands").session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON).content(command))
        val original = update().andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(worker.runOnce("updated-test")).isTrue()
        assertThat(jdbc.queryForMap("select group_id,assignee_id,priority,version from tickets where id = ?", ticketId)).containsEntry("group_id", group).containsEntry("assignee_id", assignee).containsEntry("priority", "HIGH").containsEntry("version", 2L)
        update().andExpect(status().isOk).andExpect(jsonPath("$.auditId").value(stringField(original, "auditId")))
        assertThat(jdbc.queryForObject("select count(*) from trigger_evaluation_jobs where ticket_id = ?", Long::class.java, ticketId)).isEqualTo(1L)
        assertThat(worker.runOnce("updated-test")).isFalse()
        mockMvc.perform(get("/api/v1/admin/triggers/$rule/history").session(admin.session)).andExpect(status().isOk).andExpect(jsonPath("$.versions[0].version").value(1)).andExpect(jsonPath("$.executions[0].outcome").value("MATCHED")).andExpect(jsonPath("$.jobs[0].status").value("SUCCEEDED"))
        mockMvc.perform(get("/api/v1/admin/triggers/$rule/versions/1").session(admin.session)).andExpect(status().isOk).andExpect(jsonPath("$.actions[0].type").value("SET_ASSIGNEE"))
        mockMvc.perform(get("/api/v1/admin/triggers/$rule/history")).andExpect(status().isUnauthorized)
        assertThat(jdbc.queryForList("select event.event_type from ticket_audit_events event join ticket_audits audit on audit.id = event.audit_id where audit.ticket_id = ? and audit.actor_type = 'TRIGGER' order by event.event_order", String::class.java, ticketId)).containsExactly("TRIGGER_APPLIED", "GROUP_CHANGED", "ASSIGNEE_CHANGED", "PRIORITY_CHANGED")
    }

    @Test
    fun `customer reply emits one durable root and group alert reaches only active members with trigger actor`() {
        val admin = browser()
        val ticketNumber = createUrgentTicket(admin, "reply-alert@example.com", "재답변 알림")
        val ticketId = jdbc.queryForObject("select id from tickets where ticket_number = ?", UUID::class.java, ticketNumber)!!
        val requesterId = jdbc.queryForObject("select requester_id from tickets where id = ?", UUID::class.java, ticketId)!!
        jdbc.update("update tickets set kind = 'CUSTOMER_REQUEST' where id = ?", ticketId)
        jdbc.update("update customers set verified_at = now() where id = ?", requesterId)
        val group = activeGroup("미배정 알림 그룹")
        val recipient = jdbc.queryForObject("select id from staff_accounts limit 1", UUID::class.java)!!
        jdbc.update("insert into group_memberships (id,group_id,staff_id,status,created_at,updated_at) values (?,?,?,'ACTIVE',now(),now())", UUID.randomUUID(), group, recipient)
        val outsider = browser()
        val inactiveMember = browser()
        val auditor = browser()
        jdbc.update("update staff_accounts set status = 'DISABLED' where id = ?", inactiveMember.staffId)
        jdbc.update("update staff_accounts set role = 'SECURITY_AUDITOR' where id = ?", auditor.staffId)
        for (staff in listOf(inactiveMember.staffId, auditor.staffId)) jdbc.update("insert into group_memberships (id,group_id,staff_id,status,created_at,updated_at) values (?,?,?,'ACTIVE',now(),now())", UUID.randomUUID(), group, staff)
        createAndActivate(admin, """{"name":"미배정 재답변 알림","position":1,"conditions":[{"group":"ALL","field":"EVENT","operator":"IS","value":"CUSTOMER_REPLIED"},{"group":"ALL","field":"ASSIGNEE","operator":"NOT_PRESENT"}],"actions":[{"type":"SET_GROUP","groupId":"$group"},{"type":"NOTIFY_UNASSIGNED_GROUP"}]}""")
        val id = UUID.randomUUID().toString()
        val command = dev.deskseed.ticketing.CustomerFollowUpCommand(ticketNumber, requesterId, "reply-alert@example.com", "추가 문의 내용", clientCommandId = id, context = dev.deskseed.foundation.CommandContext(dev.deskseed.foundation.RequestSource.CUSTOMER_PORTAL, "reply-alert", "reply-alert", id))
        customerPortal.addFollowUp(command)
        assertThat(customerPortal.addFollowUp(command).replayed).isTrue()
        assertThat(jdbc.queryForList("select event_type from trigger_evaluation_jobs where ticket_id = ?", String::class.java, ticketId)).containsExactly("CUSTOMER_REPLIED")
        assertThat(worker.runOnce("reply-alert-test")).isTrue()
        assertThat(jdbc.queryForObject("select count(*) from staff_notifications", Long::class.java)).isEqualTo(1L)
        mockMvc.perform(get("/api/v1/agent/notifications").session(admin.session)).andExpect(status().isOk).andExpect(jsonPath("$.items[0].type").value("UNASSIGNED_TICKET_ALERT")).andExpect(jsonPath("$.items[0].actor.type").value("TRIGGER")).andExpect(jsonPath("$.items[0].noteId").isEmpty)
        mockMvc.perform(get("/api/v1/agent/notifications").session(outsider.session)).andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(0))
        jdbc.update("update trigger_evaluation_jobs set status = 'PENDING', available_at = now(), completed_at = null where ticket_id = ?", ticketId)
        worker.runOnce("reply-alert-test")
        assertThat(jdbc.queryForObject("select count(*) from staff_notifications", Long::class.java)).isEqualTo(1L)
    }

    @Test
    fun `update durable intent failure rolls ticket audit and mutation back`() {
        val admin = browser()
        val number = createUrgentTicket(admin, "failed-update-intent@example.com", "원자적 변경")
        val group = activeGroup("업데이트 그룹")
        createAndActivate(admin, urgentUnassignedTrigger("변경 규칙", 1, group, false).replace("TICKET_CREATED", "TICKET_UPDATED"))
        val before = jdbc.queryForObject("select count(*) from ticket_audits", Long::class.java)
        jdbc.execute("create or replace function reject_rule_update_job() returns trigger language plpgsql as 'begin raise exception ''injected job failure''; end;'")
        jdbc.execute("create trigger reject_rule_update_job before insert on trigger_evaluation_jobs for each row execute function reject_rule_update_job()")
        try {
            mockMvc.perform(post("/api/v1/agent/tickets/$number/commands").session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON).content("""{"expectedVersion":0,"clientCommandId":"${UUID.randomUUID()}","changedFields":["priority"],"priority":"NORMAL"}""")).andExpect(status().is5xxServerError)
            assertThat(jdbc.queryForObject("select priority from tickets where ticket_number = ?", String::class.java, number)).isEqualTo("URGENT")
            assertThat(jdbc.queryForObject("select count(*) from ticket_audits", Long::class.java)).isEqualTo(before)
        } finally {
            jdbc.execute("drop trigger if exists reject_rule_update_job on trigger_evaluation_jobs")
            jdbc.execute("drop function if exists reject_rule_update_job()")
        }
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
        return Browser(login.request.session as MockHttpSession, token, staffId)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)
    private fun stringField(json: String, field: String): String = Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]
    private data class Browser(val session: MockHttpSession, val csrfToken: String, val staffId: UUID)
}
