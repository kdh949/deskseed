package dev.deskseed.staffaccess.internal

import dev.deskseed.integration.INTEGRATION_CLIENT_MANAGE_AUTHORITY
import dev.deskseed.integration.IntegrationAuthenticationRequest
import dev.deskseed.integration.IntegrationAuthenticationResult
import dev.deskseed.integration.IntegrationClientAdministration
import dev.deskseed.integration.IntegrationClientAuthenticator
import dev.deskseed.organization.StaffRole
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
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest(
    properties = ["deskseed.test.context-group=admin-integration-client"],
)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
@dev.deskseed.testsupport.category.IntegrationTest
class AdminIntegrationClientIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var authenticator: IntegrationClientAuthenticator
    @Autowired private lateinit var administration: IntegrationClientAdministration

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            "truncate table integration_credentials, integration_clients, staff_authority_grants, " +
                "admin_security_audit_events, audit_activity_projection cascade",
        )
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_accounts")
    }

    @Test
    fun `secret is shown once stored only as verifier and valid IP authentication records last use`(output: CapturedOutput) {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        val issueBody = createClient(browser, "orders", setOf("10.0.0.0/8"))
        val clientId = clientId(issueBody)
        val apiKey = apiKey(issueBody)
        val secret = apiKey.substringAfter('.')

        assertThat(apiKey).matches("^dsk_live_[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{43}$")
        val storedHash = jdbcTemplate.queryForObject(
            "select secret_hash from integration_credentials where client_id = ?",
            String::class.java,
            clientId,
        )!!
        assertThat(storedHash).doesNotContain(secret).doesNotContain(apiKey)

        val listBody = mockMvc.perform(get("/api/v1/admin/integration-clients").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$[0].name").value("orders"))
            .andExpect(jsonPath("$[0].apiKey").doesNotExist())
            .andReturn().response.contentAsString
        val detailBody = mockMvc.perform(
            get("/api/v1/admin/integration-clients/{clientId}", clientId).session(browser.session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.credentials[0].publicKeyId").exists())
            .andExpect(jsonPath("$.credentials[0].secretHash").doesNotExist())
            .andExpect(jsonPath("$.apiKey").doesNotExist())
            .andReturn().response.contentAsString
        assertThat(listBody).doesNotContain(secret).doesNotContain(storedHash)
        assertThat(detailBody).doesNotContain(secret).doesNotContain(storedHash)

        val result = authenticate(apiKey, "10.20.30.40", "valid")
        assertThat(result).isInstanceOf(IntegrationAuthenticationResult.Success::class.java)
        val success = result as IntegrationAuthenticationResult.Success
        assertThat(success.principal.id).isEqualTo(clientId)
        assertThat(success.principal.actorType.name).isEqualTo("INTEGRATION_CLIENT")
        assertThat(success.principal.scopes.map { it.value })
            .containsExactlyInAnyOrder("tickets:read", "tickets:update")
        assertThat(jdbcTemplate.queryForObject("select last_used_ip from integration_clients where id = ?", String::class.java, clientId))
            .isEqualTo("10.20.30.40")

        val auditPayloads = jdbcTemplate.queryForList(
            "select metadata_json from admin_security_audit_events order by occurred_at, id",
            String::class.java,
        )
        assertThat(auditPayloads).allSatisfy { payload ->
            assertThat(payload).doesNotContain(secret).doesNotContain(apiKey).doesNotContain(storedHash)
        }
        assertThat(output.all).doesNotContain(secret).doesNotContain(apiKey).doesNotContain(storedHash)
    }

    @Test
    fun `malformed wrong secret and IP denied credentials have one generic result and audited reasons`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        val apiKey = apiKey(createClient(browser, "warehouse", setOf("10.0.0.0/8")))
        val wrongSecret = apiKey.substringBefore('.') + "." + "A".repeat(43)

        assertThat(authenticate("not-an-api-key", "10.1.1.1", "malformed"))
            .isSameAs(IntegrationAuthenticationResult.Failure)
        assertThat(authenticate(wrongSecret, "10.1.1.1", "wrong"))
            .isSameAs(IntegrationAuthenticationResult.Failure)
        assertThat(authenticate(apiKey, "192.0.2.10", "ip"))
            .isSameAs(IntegrationAuthenticationResult.Failure)

        val reasons = jdbcTemplate.queryForList(
            """
            select metadata_json::jsonb ->> 'reason'
            from admin_security_audit_events
            where event_type = 'INTEGRATION_AUTHENTICATION_FAILED'
            order by occurred_at, id
            """.trimIndent(),
            String::class.java,
        )
        assertThat(reasons).containsExactlyInAnyOrder("MALFORMED_CREDENTIAL", "INVALID_SECRET", "IP_NOT_ALLOWED")
    }

    @Test
    fun `rotation allows bounded overlap then expiry revoke and disable fail closed`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        val initial = createClient(browser, "rotation", null)
        val clientId = clientId(initial)
        val oldKey = apiKey(initial)
        val rotationBody = mockMvc.perform(
            post("/api/v1/admin/integration-clients/{clientId}/rotate", clientId)
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"expiresAt":"${futureExpiry()}","overlapSeconds":3600}""",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.client.credentials[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.client.credentials[1].status").value("RETIRING"))
            .andReturn().response.contentAsString
        val newKey = apiKey(rotationBody)
        assertThat(authenticate(oldKey, "203.0.113.10", "old-overlap"))
            .isInstanceOf(IntegrationAuthenticationResult.Success::class.java)
        assertThat(authenticate(newKey, "203.0.113.10", "new"))
            .isInstanceOf(IntegrationAuthenticationResult.Success::class.java)

        jdbcTemplate.update(
            "update integration_credentials set overlap_expires_at = now() - interval '1 second' where client_id = ? and status = 'RETIRING'",
            clientId,
        )
        assertThat(authenticate(oldKey, "203.0.113.10", "old-expired"))
            .isSameAs(IntegrationAuthenticationResult.Failure)

        val activeCredentialId = jdbcTemplate.queryForObject(
            "select id from integration_credentials where client_id = ? and status = 'ACTIVE'",
            UUID::class.java,
            clientId,
        )!!
        jdbcTemplate.update(
            "update integration_credentials set created_at = now() - interval '2 days', expires_at = now() - interval '1 day' where id = ?",
            activeCredentialId,
        )
        assertThat(authenticate(newKey, "203.0.113.10", "new-expired"))
            .isSameAs(IntegrationAuthenticationResult.Failure)

        val disableIssue = createClient(browser, "disable-client", null)
        val disableId = clientId(disableIssue)
        val disableKey = apiKey(disableIssue)
        mockMvc.perform(
            post("/api/v1/admin/integration-clients/{clientId}/disable", disableId)
                .session(browser.session).csrf(browser),
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("DISABLED"))
        assertThat(authenticate(disableKey, "203.0.113.10", "disabled"))
            .isSameAs(IntegrationAuthenticationResult.Failure)

        val revokeIssue = createClient(browser, "revoke-client", null)
        val revokeId = clientId(revokeIssue)
        val revokeKey = apiKey(revokeIssue)
        mockMvc.perform(
            post("/api/v1/admin/integration-clients/{clientId}/revoke", revokeId)
                .session(browser.session).csrf(browser),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVOKED"))
            .andExpect(jsonPath("$.credentials[0].status").value("REVOKED"))
        assertThat(authenticate(revokeKey, "203.0.113.10", "revoked"))
            .isSameAs(IntegrationAuthenticationResult.Failure)
    }

    @Test
    fun `client list returns bounded current credential summaries after many rotations`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        val clientId = clientId(createClient(browser, "bounded-history", null))

        repeat(6) {
            mockMvc.perform(
                post("/api/v1/admin/integration-clients/{clientId}/rotate", clientId)
                    .session(browser.session)
                    .csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expiresAt":"${futureExpiry()}","overlapSeconds":0}"""),
            ).andExpect(status().isOk)
        }

        mockMvc.perform(get("/api/v1/admin/integration-clients").session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].credentials.length()").value(1))
            .andExpect(jsonPath("$[0].credentials[0].status").value("ACTIVE"))
        mockMvc.perform(get("/api/v1/admin/integration-clients/{clientId}", clientId).session(browser.session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.credentials.length()").value(7))
    }

    @Test
    fun `agent security auditor and admin missing the explicit authority cannot manage clients`() {
        insertStaff("agent@example.com", "Agent password 42", "AGENT")
        insertStaff("auditor@example.com", "Auditor password 42", "SECURITY_AUDITOR")
        val agent = login("agent@example.com", "Agent password 42")
        val auditor = login("auditor@example.com", "Auditor password 42")
        mockMvc.perform(get("/api/v1/admin/integration-clients").session(agent.session))
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        mockMvc.perform(get("/api/v1/admin/integration-clients").session(auditor.session))
            .andExpect(status().isForbidden)

        val principal = StaffPrincipal(
            UUID.randomUUID(),
            "admin@example.com",
            "관리자",
            StaffRole.ADMIN,
            authorities = setOf("ADMIN_MANAGE"),
        )
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN"), SimpleGrantedAuthority("ADMIN_MANAGE")),
        )
        try {
            assertThatThrownBy { administration.list() }.isInstanceOf(AccessDeniedException::class.java)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    @Test
    fun `create requires CSRF and audit persistence failure rolls back client and credential`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        mockMvc.perform(
            post("/api/v1/admin/integration-clients")
                .session(browser.session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("csrf-client", null)),
        ).andExpect(status().isForbidden)
        assertThat(jdbcTemplate.queryForObject("select count(*) from integration_clients", Long::class.java)).isZero()

        jdbcTemplate.execute(
            """
            create or replace function fail_integration_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected integration audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_integration_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_integration_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/integration-clients")
                    .session(browser.session)
                    .csrf(browser)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody("rollback-client", null)),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/admin-audit-unavailable"))
            assertThat(jdbcTemplate.queryForObject("select count(*) from integration_clients", Long::class.java)).isZero()
            assertThat(jdbcTemplate.queryForObject("select count(*) from integration_credentials", Long::class.java)).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_integration_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_integration_audit_insert()")
        }
    }

    @Test
    fun `required audit failure prevents authentication success and rolls back last use`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val browser = login("admin@example.com", "Admin password 42")
        val issue = createClient(browser, "audit-fail-auth", null)
        val clientId = clientId(issue)
        val apiKey = apiKey(issue)
        jdbcTemplate.execute(
            """
            create or replace function fail_integration_auth_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin
              if new.event_type = 'INTEGRATION_CLIENT_LAST_USED' then
                raise exception 'injected authentication audit failure';
              end if;
              return new;
            end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_integration_auth_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_integration_auth_audit_insert()",
        )
        try {
            assertThatThrownBy { authenticate(apiKey, "203.0.113.10", "audit-failure") }
                .isInstanceOf(RuntimeException::class.java)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select last_used_at from integration_clients where id = ?",
                    Timestamp::class.java,
                    clientId,
                ),
            ).isNull()
            assertThat(
                jdbcTemplate.queryForObject(
                    "select last_used_at from integration_credentials where client_id = ?",
                    Timestamp::class.java,
                    clientId,
                ),
            ).isNull()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_integration_auth_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_integration_auth_audit_insert()")
        }
    }

    @Test
    fun `rate policy is versioned audited CSRF protected and records authenticated usage without key material`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        insertStaff("agent@example.com", "Agent password 42", "AGENT")
        val browser = login("admin@example.com", "Admin password 42")
        val agent = login("agent@example.com", "Agent password 42")
        val issue = createClient(browser, "rate-policy", null)
        val clientId = clientId(issue)
        val apiKey = apiKey(issue)
        val initial = mockMvc.perform(
            get("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId).session(browser.session),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", "\"integration-client-v0\""))
            .andExpect(jsonPath("$.rateLimitPerMinute").value(60))
            .andExpect(jsonPath("$.usageCount").value(0))
            .andExpect(jsonPath("$.lastUsedIp").doesNotExist())
            .andReturn()
        assertThat(initial.response.contentAsString).doesNotContain(apiKey).doesNotContain(apiKey.substringAfter('.'))

        mockMvc.perform(
            patch("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId)
                .session(browser.session)
                .header("If-Match", "\"integration-client-v0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"rateLimitPerMinute":3}"""),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            get("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId).session(agent.session),
        ).andExpect(status().isForbidden)

        mockMvc.perform(
            patch("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId)
                .session(browser.session)
                .csrf(browser)
                .header("If-Match", "\"integration-client-v0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"rateLimitPerMinute":3}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", "\"integration-client-v1\""))
            .andExpect(jsonPath("$.rateLimitPerMinute").value(3))
            .andExpect(jsonPath("$.version").value(1))

        mockMvc.perform(
            patch("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId)
                .session(browser.session)
                .csrf(browser)
                .header("If-Match", "\"integration-client-v0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"rateLimitPerMinute":4}"""),
        )
            .andExpect(status().isPreconditionFailed)
            .andExpect(header().string("ETag", "\"integration-client-v1\""))
            .andExpect(jsonPath("$.type").value("/problems/integration-client-rate-policy-version-mismatch"))
        assertThat(
            jdbcTemplate.queryForObject(
                "select rate_limit_per_minute from integration_clients where id = ?",
                Int::class.java,
                clientId,
            ),
        ).isEqualTo(3)

        jdbcTemplate.execute(
            """
            create or replace function fail_integration_rate_policy_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin
              if new.event_type = 'INTEGRATION_CLIENT_RATE_LIMIT_UPDATED' then
                raise exception 'injected integration rate-policy audit failure';
              end if;
              return new;
            end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_integration_rate_policy_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_integration_rate_policy_audit_insert()",
        )
        try {
            mockMvc.perform(
                patch("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId)
                    .session(browser.session)
                    .csrf(browser)
                    .header("If-Match", "\"integration-client-v1\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"rateLimitPerMinute":4}"""),
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/admin-audit-unavailable"))
            assertThat(
                jdbcTemplate.queryForObject(
                    "select rate_limit_per_minute from integration_clients where id = ?",
                    Int::class.java,
                    clientId,
                ),
            ).isEqualTo(3)
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_integration_rate_policy_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_integration_rate_policy_audit_insert()")
        }

        assertThat(authenticate(apiKey, "203.0.113.10", "rate-policy-usage"))
            .isInstanceOf(IntegrationAuthenticationResult.Success::class.java)

        mockMvc.perform(
            patch("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId)
                .session(browser.session)
                .csrf(browser)
                .header("If-Match", "\"integration-client-v1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"rateLimitPerMinute":4}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"integration-client-v2\""))
            .andExpect(jsonPath("$.rateLimitPerMinute").value(4))
            .andExpect(jsonPath("$.usageCount").value(1))

        mockMvc.perform(
            get("/api/v1/admin/integration-clients/{clientId}/rate-policy", clientId).session(browser.session),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.usageCount").value(1))
            .andExpect(jsonPath("$.lastUsedAt").exists())

        val metadata = jdbcTemplate.queryForList(
            "select metadata_json from admin_security_audit_events where event_type = 'INTEGRATION_CLIENT_RATE_LIMIT_UPDATED'",
            String::class.java,
        )
        assertThat(metadata).allSatisfy { it ->
            assertThat(it)
                .contains("previousRateLimitPerMinute", "rateLimitPerMinute")
                .doesNotContain(apiKey)
                .doesNotContain(apiKey.substringAfter('.'))
        }
    }

    private fun createClient(browser: Browser, name: String, ipAllowlist: Set<String>?): String =
        mockMvc.perform(
            post("/api/v1/admin/integration-clients")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name, ipAllowlist)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.client.status").value("ACTIVE"))
            .andExpect(jsonPath("$.client.scopes.length()").value(2))
            .andReturn().response.contentAsString

    private fun createBody(name: String, ipAllowlist: Set<String>?): String {
        val ipJson = ipAllowlist?.joinToString(prefix = "[", postfix = "]") { "\"$it\"" } ?: "null"
        return """
            {
              "name":"$name",
              "description":"Order system client",
              "scopes":["tickets:read","tickets:update"],
              "resourceConstraints":{
                "allowedTicketKinds":["CUSTOMER_REQUEST"],
                "allowedFields":["status","priority"],
                "ipAllowlist":$ipJson
              },
              "expiresAt":"${futureExpiry()}"
            }
        """.trimIndent()
    }

    private fun authenticate(apiKey: String, remoteIp: String, suffix: String) = authenticator.authenticate(
        IntegrationAuthenticationRequest(apiKey, remoteIp, "req-$suffix", "corr-$suffix"),
    )

    private fun futureExpiry(): Instant = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun clientId(json: String): UUID = UUID.fromString(
        Regex("\\\"client\\\":\\{\\\"id\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1],
    )

    private fun apiKey(json: String): String =
        Regex("\\\"apiKey\\\":\\\"([^\\\"]+)\\\"").find(json)!!.groupValues[1]

    private fun login(email: String, password: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\\\"token\\\":\\\"([^\\\"]+)\\\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
        val session = csrfResult.request.session as MockHttpSession
        val result = mockMvc.perform(
            post("/api/v1/agent/session")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isNoContent).andReturn()
        return Browser(result.request.session as MockHttpSession, token)
    }

    private fun insertStaff(email: String, password: String, role: String): UUID = UUID.randomUUID().also { id ->
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, 0)
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
            when (role) {
                "ADMIN" -> "관리자"
                "SECURITY_AUDITOR" -> "감사자"
                else -> "상담사"
            },
            role,
            BCryptPasswordEncoder(4).encode(password),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
    }

    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.csrf(browser: Browser) =
        header("X-CSRF-TOKEN", browser.csrfToken)

    private data class Browser(val session: MockHttpSession, val csrfToken: String)

}
