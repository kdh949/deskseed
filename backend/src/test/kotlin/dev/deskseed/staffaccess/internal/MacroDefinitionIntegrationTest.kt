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
class MacroDefinitionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearMacros() {
        jdbc.execute(
            """
            truncate table
                access_audit_events,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                ticket_custom_field_values,
                ticket_tag_assignments,
                ticket_field_options,
                ticket_form_versions,
                ticket_forms,
                ticket_field_definitions,
                ticket_tag_definitions,
                custom_ticket_statuses,
                macro_activations,
                macro_actions,
                macro_versions,
                macro_definitions,
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
    fun `agent versions activates and lists a personal macro`() {
        val agent = browser("AGENT")
        val createdJson = mockMvc.perform(
            post("/api/v1/agent/personal-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(macroJson("긴급 답변", "안녕하세요 {{requester.name}}님. {{ticket.number}}번 문의를 확인 중입니다.")),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.scope").value("PERSONAL"))
            .andExpect(jsonPath("$.currentVersion").value(1))
            .andExpect(jsonPath("$.activeVersion").doesNotExist())
            .andExpect(jsonPath("$.actions[0].type").value("PRIORITY"))
            .andReturn().response.contentAsString
        val macroId = UUID.fromString(stringField(createdJson, "id"))

        mockMvc.perform(
            put("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.activeVersion").value(1))

        mockMvc.perform(get("/api/v1/agent/macros").session(agent.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(macroId.toString()))
            .andExpect(jsonPath("$[0].actions[1].template").value("안녕하세요 {{requester.name}}님. {{ticket.number}}번 문의를 확인 중입니다."))

        mockMvc.perform(
            post("/api/v1/agent/personal-macros/{macroId}/versions", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(macroJson("긴급 답변", "담당자가 곧 답변드리겠습니다.")),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"3\""))
            .andExpect(jsonPath("$.currentVersion").value(2))
            .andExpect(jsonPath("$.activeVersion").value(1))

        mockMvc.perform(
            put("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"2\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":2}"""),
        ).andExpect(status().isPreconditionFailed).andExpect(jsonPath("$.currentVersion").value(3))

        assertThat(jdbc.queryForList(
            "select event_type from admin_security_audit_events where target_id = ? order by occurred_at, id",
            String::class.java,
            macroId,
        )).containsExactly("MACRO_CREATED", "MACRO_ACTIVATED", "MACRO_VERSION_CREATED")
    }

    @Test
    fun `shared macro requires admin and audit failure rolls back creation`() {
        val agent = browser("AGENT")
        mockMvc.perform(
            post("/api/v1/admin/shared-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(macroJson("공유 답변", "공유 답변입니다.")),
        ).andExpect(status().isForbidden)

        val admin = browser("ADMIN")
        jdbc.execute(
            """
            create or replace function fail_macro_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.event_type = 'MACRO_CREATED' then raise exception 'injected macro audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_macro_audit_insert before insert on admin_security_audit_events for each row execute function fail_macro_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/shared-macros")
                    .session(admin.session).csrf(admin).contentType(MediaType.APPLICATION_JSON)
                    .content(macroJson("감사 실패", "저장되지 않아야 합니다.")),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_macro_audit_insert on admin_security_audit_events")
            jdbc.execute("drop function if exists fail_macro_audit_insert()")
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from macro_definitions where normalized_name = '감사 실패'",
            Long::class.java,
        )).isZero()
    }

    @Test
    fun `preview renders allowlisted placeholders without changing the ticket and is access audited`() {
        val agent = browser("AGENT")
        val ticketJson = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester":{"name":"미리보기 고객","email":"macro-preview@example.com"},
                      "subject":"환불 요청",
                      "firstComment":{"visibility":"PUBLIC","body":"환불 상태를 알려주세요."},
                      "priority":"NORMAL"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(ticketJson, "ticketNumber")
        val createdMacro = mockMvc.perform(
            post("/api/v1/agent/personal-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(macroJson("환불 긴급 응답", "{{requester.name}}님, {{ticket.number}}번 {{ticket.subject}} 문의를 확인했습니다.")),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val macroId = UUID.fromString(stringField(createdMacro, "id"))
        mockMvc.perform(
            put("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)
        val interactionId = UUID.randomUUID()

        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/macros/{macroId}/preview", ticketNumber, macroId)
                .session(agent.session).csrf(agent).header("X-Interaction-Id", interactionId),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.macroVersion").value(1))
            .andExpect(jsonPath("$.ticketVersion").value(0))
            .andExpect(jsonPath("$.changes[0].field").value("priority"))
            .andExpect(jsonPath("$.changes[0].before").value("NORMAL"))
            .andExpect(jsonPath("$.changes[0].after").value("URGENT"))
            .andExpect(jsonPath("$.comment.visibility").value("PUBLIC"))
            .andExpect(jsonPath("$.comment.body").value("미리보기 고객님, ${ticketNumber}번 환불 요청 문의를 확인했습니다."))

        assertThat(jdbc.queryForMap("select priority, version from tickets where ticket_number = ?", ticketNumber))
            .containsEntry("priority", "NORMAL")
            .containsEntry("version", 0L)
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_comments where ticket_id = (select id from tickets where ticket_number = ?)",
            Long::class.java,
            ticketNumber,
        )).isEqualTo(1)
        assertThat(jdbc.queryForList(
            "select action from access_audit_events where interaction_id = ? order by occurred_at, id",
            String::class.java,
            interactionId,
        )).containsExactly("MACRO_PREVIEWED")

        jdbc.execute(
            """
            create or replace function fail_macro_preview_audit_insert()
            returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.action = 'MACRO_PREVIEWED' then raise exception 'injected macro preview audit failure'; end if;
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "create trigger fail_macro_preview_audit_insert before insert on access_audit_events for each row execute function fail_macro_preview_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/agent/tickets/{ticketNumber}/macros/{macroId}/preview", ticketNumber, macroId)
                    .session(agent.session).csrf(agent).header("X-Interaction-Id", UUID.randomUUID()),
            ).andExpect(status().isServiceUnavailable)
        } finally {
            jdbc.execute("drop trigger if exists fail_macro_preview_audit_insert on access_audit_events")
            jdbc.execute("drop function if exists fail_macro_preview_audit_insert()")
        }
        assertThat(jdbc.queryForObject(
            "select count(*) from access_audit_events where action = 'MACRO_PREVIEWED'",
            Long::class.java,
        )).isEqualTo(1)
    }

    @Test
    fun `preview rejects a status transition that macro apply would reject and definitions reject CLOSED`() {
        val agent = browser("AGENT")
        val ticketJson = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester":{"name":"상태 전이 고객","email":"macro-status-preview@example.com"},
                      "subject":"상태 전이 미리보기",
                      "firstComment":{"visibility":"PUBLIC","body":"현재 상태를 확인해 주세요."},
                      "priority":"NORMAL"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(ticketJson, "ticketNumber")
        val createdMacro = mockMvc.perform(
            post("/api/v1/agent/personal-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"신규 보류 전이","actions":[{"type":"STATUS","status":"ON_HOLD"}]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val macroId = UUID.fromString(stringField(createdMacro, "id"))
        mockMvc.perform(
            put("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/macros/{macroId}/preview", ticketNumber, macroId)
                .session(agent.session).csrf(agent).header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.type").value("/problems/ticket-status-transition-invalid"))
        assertThat(jdbc.queryForObject(
            "select status from tickets where ticket_number = ?",
            String::class.java,
            ticketNumber,
        )).isEqualTo("NEW")

        mockMvc.perform(
            post("/api/v1/agent/personal-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"종료 상태 macro","actions":[{"type":"STATUS","status":"CLOSED"}]}"""),
        ).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject(
            "select count(*) from macro_definitions where normalized_name = '종료 상태 macro'",
            Long::class.java,
        )).isZero()
    }

    @Test
    fun `preview rejects a group-only macro that would leave the current assignee outside the target group`() {
        val agent = browser("AGENT")
        val sourceGroupId = UUID.randomUUID()
        val targetGroupId = UUID.randomUUID()
        listOf(sourceGroupId, targetGroupId).forEachIndexed { index, groupId ->
            jdbc.update(
                "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, ?, 'ACTIVE', now(), now(), 0)",
                groupId,
                "매크로 그룹 ${index + 1}",
            )
        }
        jdbc.update(
            """
            insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            UUID.randomUUID(),
            sourceGroupId,
            agent.staffId,
        )
        val ticketJson = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester":{"name":"그룹 전환 고객","email":"macro-group-preview@example.com"},
                      "subject":"그룹 전환 미리보기",
                      "firstComment":{"visibility":"PUBLIC","body":"담당 그룹을 바꿔 주세요."},
                      "priority":"NORMAL",
                      "groupId":"$sourceGroupId",
                      "assigneeId":"${agent.staffId}"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(ticketJson, "ticketNumber")
        val createdMacro = mockMvc.perform(
            post("/api/v1/agent/personal-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"그룹만 전환","actions":[{"type":"GROUP","groupId":"$targetGroupId"}]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val macroId = UUID.fromString(stringField(createdMacro, "id"))
        mockMvc.perform(
            put("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/macros/{macroId}/preview", ticketNumber, macroId)
                .session(agent.session).csrf(agent).header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.type").value("/problems/ticket-assignment-invalid"))
    }

    @Test
    fun `apply commits fields configuration and edited comment as one replayable ticket audit`() {
        val agent = browser("AGENT")
        val groupId = UUID.randomUUID()
        jdbc.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, ?, 'ACTIVE', now(), now(), 0)",
            groupId,
            "매크로 적용 그룹",
        )
        jdbc.update(
            """
            insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version)
            values (?, ?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            UUID.randomUUID(),
            groupId,
            agent.staffId,
        )
        val ticketJson = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester":{"name":"적용 고객","email":"macro-apply@example.com"},
                      "subject":"결제 확인",
                      "firstComment":{"visibility":"PUBLIC","body":"결제 내역을 확인해 주세요."},
                      "priority":"NORMAL",
                      "groupId":"$groupId",
                      "assigneeId":"${agent.staffId}"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val ticketNumber = longField(ticketJson, "ticketNumber")
        val ticketId = jdbc.queryForObject("select id from tickets where ticket_number = ?", UUID::class.java, ticketNumber)!!
        val fieldId = UUID.randomUUID()
        val formId = UUID.randomUUID()
        val tagId = UUID.randomUUID()
        val definitionJson =
            """{"placements":[{"fieldId":"$fieldId","order":0,"customer":{"visible":false,"editable":false,"required":false},"agent":{"visible":true,"editable":true,"required":false}}],"conditionalRules":[],"allowedCustomStatusIds":[]}"""
        jdbc.update(
            """
            insert into ticket_field_definitions
                (id, machine_key, field_type, staff_label, customer_visible, customer_editable, agent_visible,
                 agent_editable, searchable, analytics_eligible, sensitive, validation_json, active,
                 definition_version, created_at, updated_at)
            values (?, 'payment.reference', 'SHORT_TEXT', '결제 참조', false, false, true, true, false, false,
                    true, '{}'::jsonb, true, 1, now(), now())
            """.trimIndent(),
            fieldId,
        )
        jdbc.update(
            """
            insert into ticket_forms
                (id, name, lifecycle, default_for_customer, default_for_agent, draft_definition_json,
                 current_version, published_version, aggregate_version, created_at, updated_at)
            values (?, '매크로 적용 구성', 'DRAFT', false, true, cast(? as jsonb), 1, null, 1, now(), now())
            """.trimIndent(),
            formId,
            definitionJson,
        )
        jdbc.update(
            """
            insert into ticket_form_versions
                (form_id, version, definition_json, published_by_staff_id, published_by_display, published_at)
            values (?, 1, cast(? as jsonb), ?, '매크로 상담사', now())
            """.trimIndent(),
            formId,
            definitionJson,
            agent.staffId,
        )
        jdbc.update("update ticket_forms set lifecycle = 'PUBLISHED', published_version = 1 where id = ?", formId)
        jdbc.update(
            """
            insert into ticket_tag_definitions
                (id, normalized_value, label, active, definition_version, created_at, updated_at)
            values (?, 'payment-reviewed', '결제 확인', true, 1, now(), now())
            """.trimIndent(),
            tagId,
        )
        val macroJson =
            """
            {
              "name":"결제 확인 완료",
              "actions":[
                {"type":"PRIORITY","priority":"HIGH"},
                {"type":"ADD_TAG","tagId":"$tagId"},
                {"type":"CUSTOM_FIELD","fieldKey":"payment.reference","value":{"shortTextValue":"macro-reference-secret"}},
                {"type":"COMMENT","visibility":"INTERNAL","template":"{{ticket.number}}번 결제 확인을 완료했습니다."}
              ]
            }
            """.trimIndent()
        val createdMacro = mockMvc.perform(
            post("/api/v1/agent/personal-macros")
                .session(agent.session).csrf(agent).contentType(MediaType.APPLICATION_JSON).content(macroJson),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val macroId = UUID.fromString(stringField(createdMacro, "id"))
        mockMvc.perform(
            put("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON).content("""{"version":1}"""),
        ).andExpect(status().isOk)
        val commandId = UUID.randomUUID()
        val applyBody =
            """{"macroVersion":1,"commentBodyOverride":"상담사가 확인한 내부 결제 메모","clientCommandId":"$commandId"}"""

        val applied = mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/macros/{macroId}/apply", ticketNumber, macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn().response.contentAsString
        val auditId = UUID.fromString(stringField(applied, "auditId"))

        assertThat(jdbc.queryForMap("select priority, version from tickets where id = ?", ticketId))
            .containsEntry("priority", "HIGH")
            .containsEntry("version", 1L)
        assertThat(jdbc.queryForObject(
            "select short_text_value from ticket_custom_field_values where ticket_id = ? and field_definition_id = ?",
            String::class.java,
            ticketId,
            fieldId,
        )).isEqualTo("macro-reference-secret")
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_tag_assignments where ticket_id = ? and tag_definition_id = ?",
            Long::class.java,
            ticketId,
            tagId,
        )).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "select body from ticket_comments where ticket_id = ? order by created_at desc, id desc limit 1",
            String::class.java,
            ticketId,
        )).isEqualTo("상담사가 확인한 내부 결제 메모")
        assertThat(jdbc.queryForList(
            "select event_type from ticket_audit_events where audit_id = ? order by event_order",
            String::class.java,
            auditId,
        )).containsExactly("MACRO_APPLIED", "PRIORITY_CHANGED", "TICKET_CONFIGURATION_UPDATED", "COMMENT_CREATED")
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_audits where command_id = ?",
            Long::class.java,
            commandId.toString(),
        )).isEqualTo(1)

        mockMvc.perform(
            delete("/api/v1/agent/personal-macros/{macroId}/activation", macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"2\""),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/macros/{macroId}/apply", ticketNumber, macroId)
                .session(agent.session).csrf(agent).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(applyBody),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.replayed").value(true))
        assertThat(jdbc.queryForObject(
            "select count(*) from ticket_audits where command_id = ?",
            Long::class.java,
            commandId.toString(),
        )).isEqualTo(1)
    }

    private fun macroJson(name: String, template: String) =
        """{"name":"$name","actions":[{"type":"PRIORITY","priority":"URGENT"},{"type":"COMMENT","visibility":"PUBLIC","template":"$template"}]}"""

    private fun browser(role: String): Browser {
        val email = "macro-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Macro password 42!"
        val staffId = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            staffId, email.lowercase(), email,
            if (role == "ADMIN") "매크로 관리자" else "매크로 상담사", role,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-24T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-24T00:00:00Z")),
        )
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = stringField(csrf.response.contentAsString, "token")
        val session = csrf.request.session as MockHttpSession
        val login = mockMvc.perform(
            post("/api/v1/agent/session").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(login.request.session as MockHttpSession, token, staffId)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun stringField(json: String, field: String): String =
        Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]

    private fun longField(json: String, field: String): Long =
        Regex("\\\"$field\\\":(\\d+)").find(json)!!.groupValues[1].toLong()

    private data class Browser(val session: MockHttpSession, val csrfToken: String, val staffId: UUID)
}
