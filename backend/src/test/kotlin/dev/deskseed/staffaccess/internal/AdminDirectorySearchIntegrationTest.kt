package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.DataAccessException
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = ["deskseed.staff-auth.bootstrap.enabled=false", "deskseed.test.context-group=admin-directory-search"],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@dev.deskseed.testsupport.category.IntegrationTest
class AdminDirectorySearchIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: ObjectMapper
    private val password = "Directory fixture password 42"
    private val passwordHash = BCryptPasswordEncoder(4).encode(password)

    @BeforeEach
    fun reset() {
        jdbc.execute("truncate table staff_accounts, support_groups, access_audit_events, admin_security_audit_events, audit_activity_projection cascade")
    }

    @Test
    fun `staff and group search page across the whole directory with exact counts and literal query characters`() {
        val browser = admin()
        repeat(110) { index ->
            staff("staff-$index@example.test", "Directory ${index.toString().padStart(3, '0')}")
            group("Directory ${index.toString().padStart(3, '0')}")
        }
        search(browser, "staff", mapOf("query" to "directory", "page" to 2))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "110"))
            .andExpect(header().string("X-Total-Pages", "3"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$[0].displayName").value("Directory 100"))
        search(browser, "groups", mapOf("query" to "directory", "page" to 2))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "110"))
            .andExpect(jsonPath("$[0].name").value("Directory 100"))
        val literal = staff("literal+%_@example.test", "Literal %_\\ marker")
        search(browser, "staff", mapOf("query" to "%_\\"))
            .andExpect(status().isOk)
            .andExpect(header().string("X-Total-Count", "1"))
            .andExpect(jsonPath("$[0].id").value(literal.toString()))
        search(browser, "staff", mapOf("query" to "LITERAL+%_@EXAMPLE.TEST"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(literal.toString()))
    }

    @Test
    fun `membership filters select only members or eligible candidates and reject invalid scopes`() {
        val browser = admin()
        val groupId = group("Membership group")
        val member = staff("member@example.test", "Membership member")
        val candidate = staff("candidate@example.test", "Membership candidate")
        staff("disabled@example.test", "Membership disabled", status = "DISABLED")
        jdbc.update("insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at) values (?, ?, ?, 'ACTIVE', now(), now())", UUID.randomUUID(), groupId, member)
        search(browser, "staff", mapOf("query" to "Membership", "memberOfGroupId" to groupId))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(member.toString()))
        search(browser, "staff", mapOf("query" to "Membership", "excludeGroupId" to groupId, "status" to "ACTIVE", "role" to "AGENT"))
            .andExpect(status().isOk).andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(candidate.toString()))
        search(browser, "staff", mapOf("query" to "Membership", "excludeGroupId" to groupId, "memberOfGroupId" to groupId))
            .andExpect(status().isBadRequest)
        search(browser, "staff", mapOf("query" to "Membership", "memberOfGroupId" to UUID.randomUUID()))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("ACTIVE_GROUP_NOT_FOUND"))
        jdbc.update("update support_groups set status = 'DISABLED' where id = ?", groupId)
        search(browser, "staff", mapOf("query" to "Membership", "excludeGroupId" to groupId))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("ACTIVE_GROUP_NOT_FOUND"))
        assertThat(jdbc.queryForObject("select count(*) from access_audit_events where action = 'ADMIN_STAFF_SEARCH_EXECUTED'", Long::class.java)).isEqualTo(2)
    }

    @Test
    fun `admin authorization actor and csrf validation precede search and prevent successful audit`() {
        val browser = admin()
        val body = mapper.writeValueAsString(mapOf("query" to "private-name", "interactionId" to UUID.randomUUID()))
        mockMvc.perform(post("/api/v1/admin/staff/search").session(browser).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden)
        val anonymousCsrf = mockMvc.perform(get("/api/v1/agent/csrf")).andReturn()
        mockMvc.perform(post("/api/v1/admin/groups/search")
            .session(anonymousCsrf.request.session as MockHttpSession)
            .header("X-CSRF-TOKEN", mapper.readTree(anonymousCsrf.response.contentAsString).get("token").stringValue())
            .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        search(browser, "staff", mapOf("query" to "private-name"), UUID.randomUUID().toString())
            .andExpect(status().isConflict)
        search(browser, "staff", mapOf("query" to "private-name"), "invalid-actor")
            .andExpect(status().isBadRequest)
        for (role in listOf("AGENT", "SECURITY_AUDITOR")) {
            val email = "$role@example.test"
            staff(email, role, role = role)
            search(login(email), "staff", mapOf("query" to "private-name", "memberOfGroupId" to UUID.randomUUID()))
                .andExpect(status().isForbidden)
        }
        for (query in listOf(" ", "x".repeat(255), "private\nname")) {
            val result = search(browser, "staff", mapOf("query" to query)).andExpect(status().isBadRequest).andReturn()
            assertThat(result.response.contentAsString).doesNotContain("private\nname")
        }
        search(browser, "staff", mapOf("query" to "private-name", "size" to 101)).andExpect(status().isBadRequest)
        search(browser, "groups", mapOf("query" to "private-name", "page" to -1)).andExpect(status().isBadRequest)
        assertThat(jdbc.queryForObject("select count(*) from access_audit_events where source = 'ADMIN_UI'", Long::class.java)).isZero()
    }

    @Test
    fun `required ciphertext insert failure rolls back audit metadata and never returns results`(output: CapturedOutput) {
        val browser = admin()
        val query = "never-log-directory@example.test"
        staff(query, "Private fixture")
        jdbc.execute("create function fail_admin_search_ciphertext() returns trigger language plpgsql as 'begin raise exception ''injected failure''; end'")
        jdbc.execute("create trigger fail_admin_search_ciphertext before insert on search_audit_query_ciphertexts for each row execute function fail_admin_search_ciphertext()")
        try {
            val result = search(browser, "staff", mapOf("query" to query))
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable")).andReturn()
            assertThat(result.response.contentAsString).doesNotContain(query).doesNotContain("Private fixture")
            assertThat(output.all).doesNotContain(query)
            assertThat(jdbc.queryForObject("select count(*) from access_audit_events where source = 'ADMIN_UI'", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("select count(*) from search_audit_details", Long::class.java)).isZero()
        } finally {
            jdbc.execute("drop trigger fail_admin_search_ciphertext on search_audit_query_ciphertexts")
            jdbc.execute("drop function fail_admin_search_ciphertext()")
        }
    }

    @Test
    fun `typed directory audit exposes only safe search metadata and cannot be revealed as ticket search`(output: CapturedOutput) {
        val browser = admin()
        val query = "confidential-staff@example.test"
        search(browser, "staff", mapOf("query" to query)).andExpect(status().isOk)
        search(browser, "groups", mapOf("query" to "결제")).andExpect(status().isOk)
        val rows = jdbc.queryForList("select e.action, e.source, e.ticket_number, e.origin_search_event_id, s.query_redacted, s.result_count_relation, c.query_ciphertext from access_audit_events e join search_audit_details s on s.access_event_id=e.id join search_audit_query_ciphertexts c on c.access_event_id=e.id order by e.action")
        assertThat(rows).hasSize(2)
        rows.forEach {
            assertThat(it).containsEntry("source", "ADMIN_UI").containsEntry("ticket_number", null)
                .containsEntry("origin_search_event_id", null).containsEntry("query_redacted", "[PROTECTED]")
                .containsEntry("result_count_relation", "EXACT")
            assertThat(it["query_ciphertext"] as ByteArray).isNotEmpty()
        }
        assertThat(jdbc.queryForObject("select count(*) from search_audit_result_items", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from search_audit_customer_result_items", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("select count(*) from search_audit_query_ciphertexts where expires_at = created_at + interval '30 days'", Long::class.java)).isEqualTo(2)
        assertThatThrownBy { jdbc.update("update access_audit_events set http_status = 201 where action = 'ADMIN_STAFF_SEARCH_EXECUTED'") }.isInstanceOf(DataAccessException::class.java)
        assertThatThrownBy { jdbc.update("delete from search_audit_details") }.isInstanceOf(DataAccessException::class.java)
        assertThat(output.all).doesNotContain(query)
        val auditorId = staff("auditor@example.test", "Directory auditor", role = "SECURITY_AUDITOR")
        jdbc.update("insert into staff_authority_grants (id, staff_id, authority, granted_by_staff_id, granted_at) values (?, ?, 'AUDIT_SEARCH_QUERY_REVEAL', ?, now())", UUID.randomUUID(), auditorId, auditorId)
        val auditor = login("auditor@example.test")
        val activityId = jdbc.queryForObject("select id from audit_activity_projection where action = 'ADMIN_STAFF_SEARCH_EXECUTED'", UUID::class.java)!!
        val detail = mockMvc.perform(get("/api/v1/audit/activities/$activityId").session(auditor).header("X-Interaction-Id", UUID.randomUUID()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.protectedContentAvailable").value(false))
            .andExpect(jsonPath("$.search.queryRedacted").value("[PROTECTED]"))
            .andExpect(jsonPath("$.search.openedActivityCount").value(0)).andReturn()
        assertThat(detail.response.contentAsString).doesNotContain(query)
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf").session(auditor)).andReturn()
        mockMvc.perform(post("/api/v1/audit/activities/$activityId/search-query-reveal").session(auditor)
            .header("X-CSRF-TOKEN", mapper.readTree(csrf.response.contentAsString).get("token").stringValue())
            .header("X-Interaction-Id", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
            .content("""{"reason":"관리자 검색 경계 검증"}"""))
            .andExpect(status().isUnprocessableContent)
            .andExpect(jsonPath("$.type").value("/problems/audit-reveal-target-invalid"))
    }

    private fun admin(): MockHttpSession {
        staff("admin@example.test", "운영 관리자", role = "ADMIN")
        return login("admin@example.test")
    }

    private fun staff(email: String, name: String, role: String = "AGENT", status: String = "ACTIVE"): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into staff_accounts (id,email_normalized,email_display,display_name,role,status,password_hash,created_at,updated_at,version) values (?,?,?,?,?,?,?,now(),now(),0)", id,email.lowercase(),email,name,role,status,passwordHash)
        return id
    }

    private fun group(name: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into support_groups (id,name,status,created_at,updated_at) values (?,?,'ACTIVE',now(),now())", id,name)
        return id
    }

    private fun login(email: String): MockHttpSession {
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        return mockMvc.perform(post("/api/v1/agent/session")
            .session(csrf.request.session as MockHttpSession).header("X-CSRF-TOKEN", mapper.readTree(csrf.response.contentAsString).get("token").stringValue())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("email" to email, "password" to password))))
            .andExpect(status().isNoContent).andReturn().request.session as MockHttpSession
    }

    private fun search(browser: MockHttpSession, resource: String, fields: Map<String, Any>, expectedActor: String? = null): org.springframework.test.web.servlet.ResultActions {
        val csrf = mockMvc.perform(get("/api/v1/agent/csrf").session(browser)).andExpect(status().isOk).andReturn()
        val request = post("/api/v1/admin/$resource/search").session(browser)
            .header("X-CSRF-TOKEN", mapper.readTree(csrf.response.contentAsString).get("token").stringValue())
            .contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(fields + ("interactionId" to UUID.randomUUID())))
        expectedActor?.let { request.header("X-Deskseed-Expected-Staff-Id", it) }
        return mockMvc.perform(request)
    }
}
