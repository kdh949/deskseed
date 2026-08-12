package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class SecurityAuditorAuthorizationIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute("truncate table staff_authority_grants, admin_security_audit_events")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from staff_accounts")
    }

    @Test
    fun `allowed browser origin can preflight high risk audit authority grants`() {
        mockMvc.perform(
            options("/api/v1/admin/staff/${UUID.randomUUID()}/audit-authorities/AUDIT_SEARCH_QUERY_REVEAL")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "PUT")
                .header(
                    "Access-Control-Request-Headers",
                    "X-CSRF-TOKEN, X-Deskseed-Expected-Staff-Id",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(
                header().string(
                    "Access-Control-Allow-Methods",
                    org.hamcrest.Matchers.containsString("PUT"),
                ),
            )
    }

    @Test
    fun `security auditor receives routine read authorities only and cannot use agent or admin mutation surfaces`() {
        val auditor = insertStaff("auditor@example.com", "SECURITY_AUDITOR", "감사 담당자")
        val browser = login("auditor@example.com")

        mockMvc.perform(get("/api/v1/agent/me").session(browser))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(auditor.toString()))
            .andExpect(jsonPath("$.role").value("SECURITY_AUDITOR"))
            .andExpect(jsonPath("$.capabilities[?(@ == 'audit:activity:read')]").exists())
            .andExpect(jsonPath("$.capabilities[?(@ == 'audit:search-query:reveal')]").doesNotExist())
            .andExpect(jsonPath("$.capabilities[?(@ == 'audit:export')]").doesNotExist())
            .andExpect(jsonPath("$.capabilities[?(@ == 'audit:projection:rebuild')]").doesNotExist())
            .andExpect(jsonPath("$.capabilities[?(@ == 'AGENT_WORKSPACE')]").doesNotExist())
            .andExpect(jsonPath("$.capabilities[?(@ == 'ADMIN_MANAGE')]").doesNotExist())

        mockMvc.perform(get("/api/v1/agent/views").session(browser))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/admin/staff").session(browser))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            post("/api/v1/agent/tickets/1042/commands")
                .session(browser)
                .header("X-CSRF-TOKEN", csrf(browser))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"expectedVersion":0,"changedFields":["status"],"status":"OPEN"}""",
                ),
        ).andExpect(status().isForbidden)

    }

    @Test
    fun `admin explicitly grants and revokes one high risk audit authority with session revalidation`() {
        val adminId = insertStaff("authority-admin@example.com", "ADMIN", "권한 관리자")
        val auditorId = insertStaff("authority-auditor@example.com", "SECURITY_AUDITOR", "권한 감사자")
        val staleAuditorBrowser = login("authority-auditor@example.com")
        val adminBrowser = login("authority-admin@example.com")

        mockMvc.perform(
            put(
                "/api/v1/admin/staff/{staffId}/audit-authorities/{authority}",
                auditorId,
                "AUDIT_SEARCH_QUERY_REVEAL",
            )
                .session(adminBrowser)
                .header("X-CSRF-TOKEN", csrf(adminBrowser)),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/agent/me").session(staleAuditorBrowser))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.type").value("/problems/staff-session-invalid"))

        val grantedAuditorBrowser = login("authority-auditor@example.com")
        mockMvc.perform(get("/api/v1/agent/me").session(grantedAuditorBrowser))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.capabilities[?(@ == 'audit:search-query:reveal')]").exists())
            .andExpect(jsonPath("$.capabilities[?(@ == 'audit:export')]").doesNotExist())

        mockMvc.perform(
            delete(
                "/api/v1/admin/staff/{staffId}/audit-authorities/{authority}",
                auditorId,
                "AUDIT_SEARCH_QUERY_REVEAL",
            )
                .session(adminBrowser)
                .header("X-CSRF-TOKEN", csrf(adminBrowser)),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/agent/me").session(grantedAuditorBrowser))
            .andExpect(status().isUnauthorized)

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from staff_authority_grants where staff_id = ?",
                Long::class.java,
                auditorId,
            ),
        ).isZero()
        assertThat(
            jdbcTemplate.queryForList(
                """
                select event_type
                from admin_security_audit_events
                where actor_id = ? and target_id = ?
                order by occurred_at, id
                """.trimIndent(),
                String::class.java,
                adminId,
                auditorId,
            ),
        ).containsSubsequence("STAFF_AUTHORITY_GRANTED", "STAFF_AUTHORITY_REVOKED")
    }

    @Test
    fun `audit authority changes reject non auditors and roll back when admin audit persistence fails`() {
        insertStaff("rollback-admin@example.com", "ADMIN", "롤백 관리자")
        val auditorId = insertStaff("rollback-auditor@example.com", "SECURITY_AUDITOR", "롤백 감사자")
        val agentId = insertStaff("rollback-agent@example.com", "AGENT", "일반 상담사")
        val adminBrowser = login("rollback-admin@example.com")

        mockMvc.perform(
            put(
                "/api/v1/admin/staff/{staffId}/audit-authorities/{authority}",
                agentId,
                "AUDIT_EXPORT",
            )
                .session(adminBrowser)
                .header("X-CSRF-TOKEN", csrf(adminBrowser)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("AUDIT_AUTHORITY_TARGET_INVALID"))

        installFailingAdminAuditTrigger()
        try {
            mockMvc.perform(
                put(
                    "/api/v1/admin/staff/{staffId}/audit-authorities/{authority}",
                    auditorId,
                    "AUDIT_EXPORT",
                )
                    .session(adminBrowser)
                    .header("X-CSRF-TOKEN", csrf(adminBrowser)),
            ).andExpect(status().isServiceUnavailable)
            assertThat(authorityGrantCount(auditorId)).isZero()
        } finally {
            removeFailingAdminAuditTrigger()
        }

        mockMvc.perform(
            put(
                "/api/v1/admin/staff/{staffId}/audit-authorities/{authority}",
                auditorId,
                "AUDIT_EXPORT",
            )
                .session(adminBrowser)
                .header("X-CSRF-TOKEN", csrf(adminBrowser)),
        ).andExpect(status().isNoContent)
        assertThat(authorityGrantCount(auditorId)).isEqualTo(1)

        installFailingAdminAuditTrigger()
        try {
            mockMvc.perform(
                delete(
                    "/api/v1/admin/staff/{staffId}/audit-authorities/{authority}",
                    auditorId,
                    "AUDIT_EXPORT",
                )
                    .session(adminBrowser)
                    .header("X-CSRF-TOKEN", csrf(adminBrowser)),
            ).andExpect(status().isServiceUnavailable)
            assertThat(authorityGrantCount(auditorId)).isEqualTo(1)
        } finally {
            removeFailingAdminAuditTrigger()
        }
    }

    @Test
    fun `ordinary agent cannot use a direct audit URL`() {
        insertStaff("agent-audit-denied@example.com", "AGENT", "일반 상담사")
        val browser = login("agent-audit-denied@example.com")

        mockMvc.perform(
            get("/api/v1/audit/activities")
                .session(browser)
                .header("X-Interaction-Id", UUID.randomUUID()),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            post("/api/v1/audit/activities/{activityId}/search-query-reveal", UUID.randomUUID())
                .session(browser)
                .header("X-CSRF-TOKEN", csrf(browser))
                .header("X-Interaction-Id", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"reason":"direct URL attempt"}"""),
        ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.type").value("/problems/audit-reveal-forbidden"))

        val deniedReveal = jdbcTemplate.queryForMap(
            """
            select outcome, metadata_json
            from admin_security_audit_events
            where event_type = 'AUDIT_SENSITIVE_CONTENT_REVEALED'
              and actor_id = ?
            order by occurred_at desc
            limit 1
            """.trimIndent(),
            jdbcTemplate.queryForObject(
                "select id from staff_accounts where email_normalized = ?",
                UUID::class.java,
                "agent-audit-denied@example.com",
            ),
        )
        assertThat(deniedReveal["outcome"]).isEqualTo("DENIED")
        assertThat(deniedReveal["metadata_json"].toString())
            .contains("PERMISSION_DENIED")
            .doesNotContain("direct URL attempt")
    }

    private fun insertStaff(email: String, role: String, name: String): UUID {
        val id = UUID.randomUUID()
        val now = Instant.parse("2026-08-12T00:00:00Z")
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status,
                password_hash, created_at, updated_at, version
            ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email,
            email,
            name,
            role,
            BCryptPasswordEncoder(4).encode(PASSWORD),
            Timestamp.from(now),
            Timestamp.from(now),
        )
        return id
    }

    private fun login(email: String): MockHttpSession {
        val csrfResponse = mockMvc.perform(get("/api/v1/agent/csrf"))
            .andExpect(status().isOk)
            .andReturn()
        val browser = csrfResponse.request.session as MockHttpSession
        val csrf = stringField(csrfResponse.response.contentAsString, "token")
        mockMvc.perform(
            post("/api/v1/agent/session")
                .session(browser)
                .header("X-CSRF-TOKEN", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$PASSWORD"}"""),
        ).andExpect(status().isNoContent)
        return browser
    }

    private fun csrf(session: MockHttpSession): String = mockMvc.perform(
        get("/api/v1/agent/csrf").session(session),
    ).andExpect(status().isOk).andReturn().response.contentAsString.let { stringField(it, "token") }

    private fun stringField(json: String, field: String): String =
        Regex("\\\"$field\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(json)?.groupValues?.get(1)
            ?: error("Missing $field")

    private fun authorityGrantCount(staffId: UUID): Long = jdbcTemplate.queryForObject(
        "select count(*) from staff_authority_grants where staff_id = ?",
        Long::class.java,
        staffId,
    ) ?: 0

    private fun installFailingAdminAuditTrigger() {
        jdbcTemplate.execute(
            """
            create or replace function fail_staff_authority_audit_test() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected staff authority audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger fail_staff_authority_audit_test
            before insert on admin_security_audit_events
            for each row execute function fail_staff_authority_audit_test()
            """.trimIndent(),
        )
    }

    private fun removeFailingAdminAuditTrigger() {
        jdbcTemplate.execute(
            "drop trigger if exists fail_staff_authority_audit_test on admin_security_audit_events",
        )
        jdbcTemplate.execute("drop function if exists fail_staff_authority_audit_test()")
    }

    companion object {
        private const val PASSWORD = "Auditor password 42"

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer(
            DockerImageName.parse("postgres:18.3-alpine"),
        )
    }
}
