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
class MacroDefinitionIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearMacros() {
        jdbc.execute("truncate table macro_activations, macro_actions, macro_versions, macro_definitions cascade")
        jdbc.execute("truncate table admin_security_audit_events")
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

    private fun macroJson(name: String, template: String) =
        """{"name":"$name","actions":[{"type":"PRIORITY","priority":"URGENT"},{"type":"COMMENT","visibility":"PUBLIC","template":"$template"}]}"""

    private fun browser(role: String): Browser {
        val email = "macro-${role.lowercase()}-${UUID.randomUUID()}@example.com"
        val password = "Macro password 42!"
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(), email.lowercase(), email,
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
        return Browser(login.request.session as MockHttpSession, token)
    }

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private fun stringField(json: String, field: String): String =
        Regex("\\\"$field\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]

    private data class Browser(val session: MockHttpSession, val csrfToken: String)
}
