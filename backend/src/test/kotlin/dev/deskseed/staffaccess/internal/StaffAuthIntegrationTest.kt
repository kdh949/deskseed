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
import org.springframework.core.env.Environment
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
class StaffAuthIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var environment: Environment

    @BeforeEach
    fun clearOrganizationState() {
        jdbcTemplate.execute("truncate table admin_security_audit_events")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from staff_accounts")
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
