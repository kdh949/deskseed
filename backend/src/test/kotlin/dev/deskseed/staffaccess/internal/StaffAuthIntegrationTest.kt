package dev.deskseed.staffaccess.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.core.env.Environment
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean
import org.springframework.core.io.ClassPathResource
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.mock.web.MockHttpSession
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.staff-auth.password-cost=12",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension::class)
class StaffAuthIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var environment: Environment

    @BeforeEach
    fun clearOrganizationState() {
        jdbcTemplate.execute(
            """
            truncate table
                access_audit_events,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
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
    fun `csrf protected login returns a bounded staff session and security headers`() {
        val adminId = insertStaff("admin@example.com", "Correct horse 42", "ADMIN")
        assertThat(environment.getProperty("server.servlet.session.cookie.name"))
            .isEqualTo("DESKSEED_SESSION")
        assertThat(environment.getProperty("server.servlet.session.cookie.http-only", Boolean::class.java))
            .isTrue()
        assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax")

        mockMvc.perform(
            post("/api/v1/agent/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginJson("admin@example.com", "Correct horse 42")),
        ).andExpect(status().isForbidden)

        val browser = newBrowser()
        val login = performLogin(browser, "admin@example.com", "Correct horse 42")
            .andExpect(status().isNoContent)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
            .andReturn()

        val authenticatedSession = login.request.session as MockHttpSession
        mockMvc.perform(get("/api/v1/agent/me").session(authenticatedSession))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.id").value(adminId.toString()))
            .andExpect(jsonPath("$.email").value("admin@example.com"))
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.capabilities[0]").value("ADMIN_MANAGE"))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'LOGIN_SUCCEEDED' and actor_id = ?",
                Long::class.java,
                adminId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `confirmed tab actor mismatch is rejected before staff surfaces without invalidating the server session`() {
        val adminId = insertStaff("actor-a@example.com", "Correct horse 42", "ADMIN")
        val browser = newBrowser()
        val session = performLogin(browser, "actor-a@example.com", "Correct horse 42")
            .andExpect(status().isNoContent)
            .andReturn().request.session as MockHttpSession
        val mismatchedActorId = UUID.randomUUID()
        val accessAuditCountBefore = jdbcTemplate.queryForObject(
            "select count(*) from access_audit_events",
            Long::class.java,
        )
        val auditExplorerReadCountBefore = jdbcTemplate.queryForObject(
            "select count(*) from admin_security_audit_events where event_type = 'AUDIT_LOG_VIEWED'",
            Long::class.java,
        )
        val lastActivityBefore = session.getAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT)

        listOf(
            "/api/v1/agent/me",
            "/api/v1/agent/tickets/999999",
            "/api/v1/admin/staff",
            "/api/v1/audit/activities",
        ).forEach { path ->
            mockMvc.perform(
                get(path)
                    .session(session)
                    .header("X-Deskseed-Expected-Staff-Id", mismatchedActorId.toString()),
            )
                .andExpect(status().isConflict)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("/problems/staff-session-actor-mismatch"))
                .andExpect(jsonPath("$.status").value(409))
        }

        assertThat(jdbcTemplate.queryForObject("select count(*) from access_audit_events", Long::class.java))
            .isEqualTo(accessAuditCountBefore)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'AUDIT_LOG_VIEWED'",
                Long::class.java,
            ),
        ).isEqualTo(auditExplorerReadCountBefore)
        assertThat(session.getAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT))
            .isEqualTo(lastActivityBefore)

        listOf("", " ", "not-a-uuid").forEach { malformedActor ->
            mockMvc.perform(
                get("/api/v1/agent/me")
                    .session(session)
                    .header("X-Deskseed-Expected-Staff-Id", malformedActor),
            )
                .andExpect(status().isBadRequest)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.type").value("/problems/invalid-staff-session-actor"))
                .andExpect(jsonPath("$.status").value(400))
        }
        mockMvc.perform(
            get("/api/v1/agent/me")
                .session(session)
                .header(
                    "X-Deskseed-Expected-Staff-Id",
                    adminId.toString(),
                    mismatchedActorId.toString(),
                ),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.type").value("/problems/invalid-staff-session-actor"))
        assertThat(session.getAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT))
            .isEqualTo(lastActivityBefore)

        mockMvc.perform(
            delete("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("X-Deskseed-Expected-Staff-Id", mismatchedActorId.toString()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.type").value("/problems/staff-session-actor-mismatch"))
        assertThat(session.getAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT))
            .isEqualTo(lastActivityBefore)

        mockMvc.perform(
            get("/api/v1/agent/me")
                .session(session)
                .header("X-Deskseed-Expected-Staff-Id", adminId.toString()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(adminId.toString()))

        mockMvc.perform(get("/api/v1/agent/me").session(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(adminId.toString()))
    }

    @Test
    fun `confirmed tab actor mismatch blocks a real ticket command before mutation audit or activity`() {
        val actorA = insertStaff("command-actor-a@example.com", "Correct horse 42", "AGENT")
        val groupId = insertGroup("actor guard command group", actorA)
        val browser = newBrowser()
        val session = performLogin(browser, "command-actor-a@example.com", "Correct horse 42")
            .andExpect(status().isNoContent)
            .andReturn().request.session as MockHttpSession

        val created = mockMvc.perform(
            post("/api/v1/agent/tickets")
                .session(session)
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("X-Deskseed-Expected-Staff-Id", actorA.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "requester": {
                        "name": "Actor guard customer",
                        "email": "actor-guard-customer@example.com"
                      },
                      "subject": "Actor guard mutation boundary",
                      "firstComment": {
                        "visibility": "PUBLIC",
                        "body": "Initial public request"
                      },
                      "priority": "NORMAL",
                      "groupId": "$groupId",
                      "assigneeId": "$actorA"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val ticketNumber = Regex("\"ticketNumber\":(\\d+)")
            .find(created)!!.groupValues[1].toLong()
        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            ticketNumber,
        )!!
        val versionBefore = jdbcTemplate.queryForObject(
            "select version from tickets where id = ?",
            Long::class.java,
            ticketId,
        )
        val commentCountBefore = jdbcTemplate.queryForObject(
            "select count(*) from ticket_comments where ticket_id = ?",
            Long::class.java,
            ticketId,
        )
        val auditCountBefore = jdbcTemplate.queryForObject(
            "select count(*) from ticket_audits where ticket_id = ?",
            Long::class.java,
            ticketId,
        )
        val auditEventCountBefore = jdbcTemplate.queryForObject(
            """
            select count(*)
            from ticket_audit_events event
            join ticket_audits audit on audit.id = event.audit_id
            where audit.ticket_id = ?
            """.trimIndent(),
            Long::class.java,
            ticketId,
        )
        val lastActivityBefore = session.getAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT)

        mockMvc.perform(
            post("/api/v1/agent/tickets/{ticketNumber}/commands", ticketNumber)
                .session(session)
                .header("X-CSRF-TOKEN", browser.csrfToken)
                .header("X-Deskseed-Expected-Staff-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion": $versionBefore,
                      "changedFields": ["priority"],
                      "priority": "HIGH",
                      "comment": {
                        "visibility": "INTERNAL",
                        "body": "This must not be persisted"
                      },
                      "clientCommandId": "11111111-1111-4111-8111-111111111111"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.type").value("/problems/staff-session-actor-mismatch"))
            .andExpect(jsonPath("$.status").value(409))

        assertThat(
            jdbcTemplate.queryForMap(
                "select version, priority from tickets where id = ?",
                ticketId,
            ),
        )
            .containsEntry("version", versionBefore)
            .containsEntry("priority", "NORMAL")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_comments where ticket_id = ?",
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(commentCountBefore)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ticket_audits where ticket_id = ?",
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(auditCountBefore)
        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from ticket_audit_events event
                join ticket_audits audit on audit.id = event.audit_id
                where audit.ticket_id = ?
                """.trimIndent(),
                Long::class.java,
                ticketId,
            ),
        ).isEqualTo(auditEventCountBefore)
        assertThat(session.getAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT))
            .isEqualTo(lastActivityBefore)
    }

    @Test
    fun `cors preflight allows the reserved expected staff actor header`() {
        mockMvc.perform(
            options("/api/v1/agent/me")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "X-Deskseed-Expected-Staff-Id"),
        )
            .andExpect(status().isOk)
            .andExpect(
                header().string(
                    "Access-Control-Allow-Headers",
                    org.hamcrest.Matchers.containsStringIgnoringCase("X-Deskseed-Expected-Staff-Id"),
                ),
            )
    }

    @Test
    fun `production profile requires secure session cookies`() {
        val production = YamlPropertiesFactoryBean().apply {
            setResources(ClassPathResource("application-production.yml"))
        }.getObject()

        assertThat(production?.getProperty("server.servlet.session.cookie.secure"))
            .isEqualTo("true")
    }

    @Test
    fun `unknown wrong-password and disabled login share one generic credential response`() {
        insertStaff("active@example.com", "Correct horse 42", "AGENT")
        insertStaff("disabled@example.com", "Correct horse 42", "AGENT", status = "DISABLED")

        val attempts = listOf(
            "unknown@example.com" to "Wrong password 42",
            "active@example.com" to "Wrong password 42",
            "disabled@example.com" to "Correct horse 42",
        )

        attempts.forEach { (email, password) ->
            val browser = newBrowser()
            performLogin(browser, email, password)
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/problems/invalid-staff-credentials"))
                .andExpect(jsonPath("$.detail").value("The email or password is invalid."))
        }

        val failedAudits = jdbcTemplate.queryForList(
            """
            select metadata_json
            from admin_security_audit_events
            where event_type = 'LOGIN_FAILED'
              and request_id like 'auth-test-%'
            """.trimIndent(),
            String::class.java,
        )
        assertThat(failedAudits).hasSize(3)
        assertThat(failedAudits.joinToString()).doesNotContain("Wrong password 42", "Correct horse 42")
    }

    @Test
    fun `staff credentials session headers and forged identifiers never appear in application output`(
        output: CapturedOutput,
    ) {
        val marker = UUID.randomUUID().toString()
        val password = "staff-credential-$marker"
        val authorization = "Bearer authorization-$marker"
        val forgedRequestId = "request-$marker\nforged-log-$marker"
        insertStaff("log-safety@example.com", password, "AGENT")

        val browser = newBrowser()
        val login = performLogin(browser, "log-safety@example.com", password)
            .andExpect(status().isNoContent)
            .andReturn()
        val session = login.request.session as MockHttpSession
        val sessionCookie = "DESKSEED_SESSION=session-cookie-$marker"

        mockMvc.perform(
            get("/api/v1/agent/me")
                .session(session)
                .header("Authorization", authorization)
                .header("Cookie", sessionCookie)
                .header("X-Request-Id", forgedRequestId),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Request-Id", org.hamcrest.Matchers.not(forgedRequestId)))

        assertThat(output.all)
            .doesNotContain(password)
            .doesNotContain(authorization)
            .doesNotContain(sessionCookie)
            .doesNotContain("session-cookie-$marker")
            .doesNotContain(forgedRequestId)
            .doesNotContain("forged-log-$marker")
            .doesNotContain("Using generated security password:")
    }

    @Test
    fun `ten failures lock the email and network key without revealing account existence`() {
        insertStaff("limited@example.com", "Correct horse 42", "AGENT")
        val browser = newBrowser()

        repeat(9) { attempt ->
            performLogin(browser, "limited@example.com", "Wrong password $attempt")
                .andExpect(status().isUnauthorized)
        }

        performLogin(browser, "limited@example.com", "Wrong password final")
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.type").value("/problems/staff-login-rate-limited"))

        performLogin(browser, "limited@example.com", "Correct horse 42")
            .andExpect(status().isTooManyRequests)
    }

    @Test
    fun `logout absolute expiry and disabled status each invalidate protected access`() {
        val staffId = insertStaff("agent@example.com", "Correct horse 42", "AGENT")

        val logoutBrowser = newBrowser()
        val logoutSession = performLogin(logoutBrowser, "agent@example.com", "Correct horse 42")
            .andExpect(status().isNoContent)
            .andReturn().request.session as MockHttpSession
        mockMvc.perform(
            delete("/api/v1/agent/session")
                .session(logoutSession)
                .header("X-CSRF-TOKEN", logoutBrowser.csrfToken),
        ).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/agent/me").session(logoutSession))
            .andExpect(status().isUnauthorized)

        val expiredBrowser = newBrowser()
        val expiredSession = performLogin(expiredBrowser, "agent@example.com", "Correct horse 42")
            .andExpect(status().isNoContent)
            .andReturn().request.session as MockHttpSession
        expiredSession.setAttribute("deskseed.staff.session.absolute-expires-at", Instant.EPOCH)
        mockMvc.perform(get("/api/v1/agent/me").session(expiredSession))
            .andExpect(status().isUnauthorized)

        val disabledBrowser = newBrowser()
        val disabledSession = performLogin(disabledBrowser, "agent@example.com", "Correct horse 42")
            .andExpect(status().isNoContent)
            .andReturn().request.session as MockHttpSession
        jdbcTemplate.update(
            "update staff_accounts set status = 'DISABLED', updated_at = now(), version = version + 1 where id = ?",
            staffId,
        )
        mockMvc.perform(get("/api/v1/agent/me").session(disabledSession))
            .andExpect(status().isUnauthorized)
    }

    private fun newBrowser(): BrowserSession {
        val result = mockMvc.perform(
            get("/api/v1/agent/csrf")
                .header("X-Request-Id", "auth-test-${UUID.randomUUID()}"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"")
            .find(result.response.contentAsString)!!.groupValues[1]
        return BrowserSession(result.request.session as MockHttpSession, token)
    }

    private fun performLogin(
        browser: BrowserSession,
        email: String,
        password: String,
    ) = mockMvc.perform(
        post("/api/v1/agent/session")
            .session(browser.session)
            .header("X-CSRF-TOKEN", browser.csrfToken)
            .header("X-Request-Id", "auth-test-${UUID.randomUUID()}")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginJson(email, password)),
    )

    private fun insertStaff(
        email: String,
        password: String,
        role: String,
        status: String = "ACTIVE",
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            "테스트 직원",
            role,
            status,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
        return id
    }

    private fun insertGroup(name: String, vararg members: UUID): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            id,
            "$name-${UUID.randomUUID()}",
        )
        members.forEach { staffId ->
            jdbcTemplate.update(
                """
                insert into group_memberships
                    (id, group_id, staff_id, status, created_at, updated_at, version)
                values (?, ?, ?, 'ACTIVE', now(), now(), 0)
                """.trimIndent(),
                UUID.randomUUID(),
                id,
                staffId,
            )
        }
        return id
    }

    private fun loginJson(email: String, password: String): String =
        """{"email":"$email","password":"$password"}"""

    private data class BrowserSession(
        val session: MockHttpSession,
        val csrfToken: String,
    )

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
