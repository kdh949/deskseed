package dev.deskseed.staffaccess.internal

import com.sun.net.httpserver.HttpServer
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.net.InetSocketAddress
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@AutoConfigureMockMvc
@Testcontainers
class ExternalReferenceIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            """
            truncate table
                external_references,
                external_systems,
                access_audit_events,
                admin_security_audit_events,
                audit_activity_projection,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
                tickets,
                customers,
                group_memberships,
                support_groups,
                staff_authority_grants,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
    }

    @Test
    fun `admin registry is separately authorized versioned and security audited`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        insertStaff("agent@example.com", "Agent password 42", "AGENT")
        insertStaff("auditor@example.com", "Auditor password 42", "SECURITY_AUDITOR")
        val admin = login("admin@example.com", "Admin password 42")
        val agent = login("agent@example.com", "Agent password 42")
        val auditor = login("auditor@example.com", "Auditor password 42")

        mockMvc.perform(get("/api/v1/admin/external-systems").session(agent.session))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/admin/external-systems").session(auditor.session))
            .andExpect(status().isForbidden)

        val created = createSystem(admin, "shop-order", "Shop Order", "admin.shop.example")
        val systemId = jsonUuid(created, "id")
        mockMvc.perform(
            put("/api/v1/admin/external-systems/{systemId}", systemId)
                .session(admin.session)
                .csrf(admin)
                .header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "displayName":"Shop Order Console",
                      "status":"DISABLED",
                      "allowedHostnames":["orders.shop.example"],
                      "expectedVersion":0
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.systemKey").value("shop-order"))
            .andExpect(jsonPath("$.status").value("DISABLED"))
            .andExpect(jsonPath("$.allowedHostnames[0]").value("orders.shop.example"))

        assertThat(
            jdbcTemplate.queryForList(
                "select event_type from admin_security_audit_events " +
                    "where event_type like 'EXTERNAL_SYSTEM_%' order by occurred_at, id",
                String::class.java,
            ),
        ).containsExactly("EXTERNAL_SYSTEM_CREATED", "EXTERNAL_SYSTEM_UPDATED")

        mockMvc.perform(
            post("/api/v1/admin/external-systems")
                .session(admin.session)
                .csrf(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"systemKey":"private-host","displayName":"Private","allowedHostnames":["127.0.0.1"]}""",
                ),
        ).andExpect(status().isBadRequest)

        jdbcTemplate.execute(
            """
            create or replace function fail_external_system_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin
              if new.event_type = 'EXTERNAL_SYSTEM_CREATED' then
                raise exception 'injected external system audit failure';
              end if;
              return new;
            end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_external_system_audit before insert on admin_security_audit_events " +
                "for each row execute function fail_external_system_audit_insert()",
        )
        try {
            mockMvc.perform(
                post("/api/v1/admin/external-systems")
                    .session(admin.session)
                    .csrf(admin)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"systemKey":"rollback-system","displayName":"Rollback","allowedHostnames":["rollback.shop.example"]}""",
                    ),
            ).andExpect(status().isServiceUnavailable)
            assertThat(
                jdbcTemplate.queryForObject(
                    "select count(*) from external_systems where system_key = 'rollback-system'",
                    Long::class.java,
                ),
            ).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_external_system_audit on admin_security_audit_events")
            jdbcTemplate.execute("drop function if exists fail_external_system_audit_insert()")
        }
    }

    @Test
    fun `reference lifecycle is atomic audited duplicate safe and absent from customer projection`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        insertStaff("agent@example.com", "Agent password 42", "AGENT")
        insertStaff("auditor@example.com", "Auditor password 42", "SECURITY_AUDITOR")
        val admin = login("admin@example.com", "Admin password 42")
        val agent = login("agent@example.com", "Agent password 42")
        val auditor = login("auditor@example.com", "Auditor password 42")
        val customer = createPublicTicket()
        val systemId = jsonUuid(createSystem(admin, "shop-order", "Shop Order", "admin.shop.example"), "id")

        createReference(agent, customer.ticketNumber, systemId, 0, "order-agent-denied")
            .andExpect(status().isForbidden)
        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/external-references", customer.ticketNumber)
                .session(auditor.session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        ).andExpect(status().isForbidden)
        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/external-references", customer.ticketNumber)
                .header("X-Interaction-Id", UUID.randomUUID()),
        ).andExpect(status().isUnauthorized)

        val created = createReference(admin, customer.ticketNumber, systemId, 0, "order-100")
            .andExpect(status().isCreated)
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.ticketVersion").value(1))
            .andExpect(jsonPath("$.reference.safeDeepLink").value("https://admin.shop.example/orders/100?tab=summary"))
            .andExpect(jsonPath("$.reference.metadata.status").value("paid"))
            .andReturn().response.contentAsString
        val referenceId = jsonUuid(created, "id")

        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/external-references", customer.ticketNumber)
                .session(admin.session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.canManage").value(true))
            .andExpect(jsonPath("$.items[0].externalId").value("order-100"))

        mockMvc.perform(
            get("/api/v1/agent/tickets/{ticketNumber}/external-references", customer.ticketNumber)
                .session(agent.session)
                .header("X-Interaction-Id", UUID.randomUUID()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canManage").value(false))
            .andExpect(jsonPath("$.items[0].externalId").value("order-100"))

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where action = 'API_RESOURCE_READ'",
                Long::class.java,
            ),
        ).isEqualTo(2)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from access_audit_events where action = 'TICKET_VIEWED'",
                Long::class.java,
            ),
        ).isZero()

        createReference(admin, customer.ticketNumber, systemId, 1, "order-100")
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("EXTERNAL_REFERENCE_EXISTS"))
        assertThat(ticketVersion(customer.ticketNumber)).isEqualTo(1)
        assertThat(ticketAuditEventCount("EXTERNAL_REFERENCE_CREATED")).isEqualTo(1)

        val customerBody = mockMvc.perform(
            get("/api/v1/requests/{ticketNumber}", customer.ticketNumber)
                .header("X-Request-Access-Token", customer.accessToken),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        assertThat(customerBody)
            .doesNotContain("externalReference")
            .doesNotContain("order-100")
            .doesNotContain("admin.shop.example")

        mockMvc.perform(
            delete(
                "/api/v1/agent/tickets/{ticketNumber}/external-references/{referenceId}",
                customer.ticketNumber,
                referenceId,
            )
                .session(admin.session)
                .csrf(admin)
                .header("If-Match", "\"1\""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("ETag", "\"2\""))
            .andExpect(jsonPath("$.removedReferenceId").value(referenceId.toString()))
        assertThat(jdbcTemplate.queryForObject("select count(*) from external_references", Long::class.java)).isZero()
        assertThat(ticketAuditEventCount("EXTERNAL_REFERENCE_REMOVED")).isEqualTo(1)

        val auditJson = jdbcTemplate.queryForList(
            "select metadata_json from ticket_audit_events where event_type like 'EXTERNAL_REFERENCE_%'",
            String::class.java,
        )
        assertThat(auditJson).allSatisfy { metadata ->
            assertThat(metadata)
                .contains("shop-order", "order-100", "admin.shop.example")
                .doesNotContain("paid", "tab=summary", "/orders/100")
        }
    }

    @Test
    fun `inactive malicious and private candidates fail without persistence audit or server fetch`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val admin = login("admin@example.com", "Admin password 42")
        val customer = createPublicTicket()
        val systemId = jsonUuid(createSystem(admin, "ops-case", "Ops Case", "ops.shop.example"), "id")
        val fetchCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                fetchCount.incrementAndGet()
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            start()
        }
        try {
            val badLinks = listOf(
                "http://ops.shop.example/cases/1",
                "https://staff:secret@ops.shop.example/cases/1",
                "https://evil.example/cases/1",
                "https://ops.shop.example/cases/1?access_token=secret",
                "https://127.0.0.1:${server.address.port}/cases/1",
            )
            badLinks.forEachIndexed { index, link ->
                createReference(admin, customer.ticketNumber, systemId, 0, "ops-$index", link)
                    .andExpect(status().isBadRequest)
            }
            assertThat(fetchCount).hasValue(0)
            assertThat(jdbcTemplate.queryForObject("select count(*) from external_references", Long::class.java)).isZero()
            assertThat(ticketAuditEventCount("EXTERNAL_REFERENCE_CREATED")).isZero()

            mockMvc.perform(
                put("/api/v1/admin/external-systems/{systemId}", systemId)
                    .session(admin.session)
                    .csrf(admin)
                    .header("If-Match", "0")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"displayName":"Ops Case","status":"DISABLED","allowedHostnames":["ops.shop.example"],"expectedVersion":0}""",
                    ),
            ).andExpect(status().isOk)
            createReference(admin, customer.ticketNumber, systemId, 0, "ops-disabled")
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("EXTERNAL_SYSTEM_INACTIVE"))
            assertThat(fetchCount).hasValue(0)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `ticket audit failure rolls back reference and ticket version`() {
        insertStaff("admin@example.com", "Admin password 42", "ADMIN")
        val admin = login("admin@example.com", "Admin password 42")
        val customer = createPublicTicket()
        val systemId = jsonUuid(createSystem(admin, "refund", "Refund", "refund.shop.example"), "id")
        jdbcTemplate.execute(
            """
            create or replace function fail_external_reference_audit_insert() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected reference audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_external_reference_audit before insert on ticket_audits " +
                "for each row execute function fail_external_reference_audit_insert()",
        )
        try {
            createReference(
                admin,
                customer.ticketNumber,
                systemId,
                0,
                "refund-100",
                "https://refund.shop.example/refunds/100",
            )
                .andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.type").value("/problems/audit-write-unavailable"))
            assertThat(jdbcTemplate.queryForObject("select count(*) from external_references", Long::class.java)).isZero()
            assertThat(ticketVersion(customer.ticketNumber)).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_external_reference_audit on ticket_audits")
            jdbcTemplate.execute("drop function if exists fail_external_reference_audit_insert()")
        }
    }

    private fun createSystem(browser: Browser, key: String, name: String, host: String): String =
        mockMvc.perform(
            post("/api/v1/admin/external-systems")
                .session(browser.session)
                .csrf(browser)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"systemKey":"$key","displayName":"$name","allowedHostnames":["$host"]}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString

    private fun createReference(
        browser: Browser,
        ticketNumber: Long,
        systemId: UUID,
        version: Long,
        externalId: String,
        link: String = "https://admin.shop.example/orders/100?tab=summary",
    ) = mockMvc.perform(
        post("/api/v1/agent/tickets/{ticketNumber}/external-references", ticketNumber)
            .session(browser.session)
            .csrf(browser)
            .header("If-Match", "\"$version\"")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "externalSystemId":"$systemId",
                  "objectType":"ORDER",
                  "externalId":"$externalId",
                  "displayLabel":"Order 100",
                  "safeDeepLink":"$link",
                  "metadata":{"status":"paid","amountDisplay":12900},
                  "metadataObservedAt":"${Instant.now().truncatedTo(ChronoUnit.SECONDS)}",
                  "expectedVersion":$version
                }
                """.trimIndent(),
            ),
    )

    private fun createPublicTicket(): CustomerTicket {
        val body = mockMvc.perform(
            post("/api/v1/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name":"External Customer",
                      "email":"external-customer@example.com",
                      "subject":"Order refund question",
                      "message":"Please check my refund"
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return CustomerTicket(
            ticketNumber = Regex("\"ticketNumber\":([0-9]+)").find(body)!!.groupValues[1].toLong(),
            accessToken = Regex("\"accessToken\":\"([^\"]+)\"").find(body)!!.groupValues[1],
        )
    }

    private fun login(email: String, password: String): Browser {
        val csrfResult = mockMvc.perform(get("/api/v1/agent/csrf")).andExpect(status().isOk).andReturn()
        val token = Regex("\"token\":\"([^\"]+)\"").find(csrfResult.response.contentAsString)!!.groupValues[1]
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

    private fun ticketVersion(ticketNumber: Long): Long = jdbcTemplate.queryForObject(
        "select version from tickets where ticket_number = ?",
        Long::class.java,
        ticketNumber,
    )!!

    private fun ticketAuditEventCount(eventType: String): Long = jdbcTemplate.queryForObject(
        "select count(*) from ticket_audit_events where event_type = ?",
        Long::class.java,
        eventType,
    )!!

    private fun jsonUuid(json: String, field: String): UUID = UUID.fromString(
        Regex("\"$field\":\"([^\"]+)\"").find(json)!!.groupValues[1],
    )

    private fun MockHttpServletRequestBuilder.csrf(browser: Browser) = header("X-CSRF-TOKEN", browser.csrfToken)

    private data class Browser(val session: MockHttpSession, val csrfToken: String)
    private data class CustomerTicket(val ticketNumber: Long, val accessToken: String)

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
