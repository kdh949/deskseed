package dev.deskseed.platformapi.internal

import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.CreateIntegrationClientCommand
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientAdministration
import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationScope
import dev.deskseed.integration.IntegrationTicketField
import dev.deskseed.integration.IntegrationTicketKind
import dev.deskseed.ticketing.TicketingFacade
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@SpringBootTest(
    properties = [
        "deskseed.staff-auth.bootstrap.enabled=false",
        "deskseed.platform.rate-limit.requests-per-minute=100",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PlatformTicketIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var administration: IntegrationClientAdministration
    @Autowired private lateinit var ticketingFacade: TicketingFacade
    @Autowired private lateinit var objectMapper: ObjectMapper

    private lateinit var adminId: UUID

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            "truncate table platform_idempotency_records, integration_credentials, integration_clients, " +
                "access_audit_events, admin_security_audit_events, ticket_audit_events, ticket_audits, " +
                "ticket_comments, tickets, group_memberships, support_groups, staff_authority_grants, " +
                "staff_accounts, customers cascade",
        )
        jdbcTemplate.execute("alter sequence ticket_number_seq restart with 1000")
        adminId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts (
                id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at
            ) values (?, 'admin@example.com', 'admin@example.com', 'Admin', 'ADMIN', 'ACTIVE', 'unused', now(), now())
            """.trimIndent(),
            adminId,
        )
    }

    @Test
    fun `customer request replay update read and internal comment preserve actor audit and public projection`() {
        val key = issueClient(IntegrationScope.entries.toSet())
        val create = perform(
            post("/api/v1/platform/tickets"),
            key,
            "create-0001",
            customerRequest("Original"),
        )
        assertThat(create.response.status).isEqualTo(201)
        assertThat(create.response.getHeader("ETag")).isEqualTo("\"ticket-v0\"")
        assertThat(create.response.getHeader("X-RateLimit-Limit")).isEqualTo("100")
        val ticketNumber = objectMapper.readTree(create.response.contentAsString).get("ticketNumber").asLong()

        val replay = perform(
            post("/api/v1/platform/tickets"),
            key,
            "create-0001",
            customerRequest("Original"),
        )
        assertThat(replay.response.status).isEqualTo(201)
        assertThat(replay.response.contentAsString).isEqualTo(create.response.contentAsString)
        assertThat(count("tickets")).isEqualTo(1)
        assertThat(count("ticket_audits")).isEqualTo(1)

        val read = mockMvc.perform(
            authorized(get("/api/v1/platform/tickets/{ticketNumber}", ticketNumber), key),
        ).andReturn()
        assertThat(read.response.status).isEqualTo(200)
        assertThat(read.response.contentAsString).doesNotContain("requester").doesNotContain("comments")
        assertThat(countWhere("access_audit_events", "action = 'API_RESOURCE_READ' and actor_type = 'INTEGRATION_CLIENT'"))
            .isEqualTo(1)

        val update = perform(
            patch("/api/v1/platform/tickets/{ticketNumber}", ticketNumber).header("If-Match", "\"ticket-v0\""),
            key,
            "update-0001",
            """{"status":"OPEN"}""",
        )
        assertThat(update.response.status).isEqualTo(200)
        assertThat(update.response.getHeader("ETag")).isEqualTo("\"ticket-v1\"")

        val comment = perform(
            post("/api/v1/platform/tickets/{ticketNumber}/internal-comments", ticketNumber),
            key,
            "comment-0001",
            """{"body":"private investigation"}""",
        )
        assertThat(comment.response.status).isEqualTo(201)
        assertThat(comment.response.getHeader("ETag")).isEqualTo("\"ticket-v2\"")

        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            ticketNumber,
        )!!
        val public = ticketingFacade.findPublicTicket(ticketId, ticketNumber)!!
        assertThat(public.comments.map { it.body }).containsExactly("Original")
        assertThat(jdbcTemplate.queryForList("select actor_type from ticket_audits", String::class.java))
            .containsOnly("INTEGRATION_CLIENT")
        assertThat(jdbcTemplate.queryForList("select source from ticket_audits", String::class.java))
            .containsOnly("PLATFORM_API")
        assertThat(jdbcTemplate.queryForList("select author_type from ticket_comments order by created_at", String::class.java))
            .containsExactly("CUSTOMER", "INTEGRATION_CLIENT")
    }

    @Test
    fun `internal work item has no fabricated requester and starts with an internal machine comment`() {
        val key = issueClient(
            setOf(IntegrationScope.TICKETS_CREATE, IntegrationScope.TICKETS_READ),
            IntegrationResourceConstraints(allowedTicketKinds = setOf(IntegrationTicketKind.INTERNAL_TASK)),
        )
        val result = perform(
            post("/api/v1/platform/tickets"),
            key,
            "internal-0001",
            """{"kind":"INTERNAL_WORK_ITEM","subject":"Reconcile orders","message":"Check batch 42"}""",
        )
        assertThat(result.response.status).isEqualTo(201)
        assertThat(jdbcTemplate.queryForObject("select requester_id is null from tickets", Boolean::class.java)).isTrue()
        assertThat(jdbcTemplate.queryForObject("select kind from tickets", String::class.java)).isEqualTo("INTERNAL_WORK_ITEM")
        assertThat(jdbcTemplate.queryForObject("select visibility from ticket_comments", String::class.java)).isEqualTo("INTERNAL")
        assertThat(jdbcTemplate.queryForObject("select author_type from ticket_comments", String::class.java))
            .isEqualTo("INTEGRATION_CLIENT")
        assertThat(count("customers")).isZero()
    }

    @Test
    fun `scope kind and field constraints deny without mutation and record security reason`() {
        val createOnly = issueClient(setOf(IntegrationScope.TICKETS_CREATE))
        val created = perform(post("/api/v1/platform/tickets"), createOnly, "scope-create", customerRequest("scope"))
        val ticketNumber = objectMapper.readTree(created.response.contentAsString).get("ticketNumber").asLong()
        val missingRead = mockMvc.perform(
            authorized(get("/api/v1/platform/tickets/{ticketNumber}", ticketNumber), createOnly),
        ).andReturn()
        assertThat(missingRead.response.status).isEqualTo(403)

        val internalOnly = issueClient(
            setOf(IntegrationScope.TICKETS_CREATE),
            IntegrationResourceConstraints(allowedTicketKinds = setOf(IntegrationTicketKind.INTERNAL_TASK)),
        )
        val deniedKind = perform(
            post("/api/v1/platform/tickets"),
            internalOnly,
            "denied-kind",
            customerRequest("denied"),
        )
        assertThat(deniedKind.response.status).isEqualTo(403)

        val statusOnly = issueClient(
            setOf(IntegrationScope.TICKETS_UPDATE),
            IntegrationResourceConstraints(allowedFields = setOf(IntegrationTicketField.STATUS)),
        )
        val deniedField = perform(
            patch("/api/v1/platform/tickets/{ticketNumber}", ticketNumber).header("If-Match", "\"ticket-v0\""),
            statusOnly,
            "denied-field",
            """{"priority":"HIGH"}""",
        )
        assertThat(deniedField.response.status).isEqualTo(403)
        assertThat(jdbcTemplate.queryForObject("select priority from tickets where ticket_number = ?", String::class.java, ticketNumber))
            .isEqualTo("NORMAL")
        assertThat(
            countWhere(
                "admin_security_audit_events",
                "event_type = 'ACCESS_DENIED' and metadata_json like '%DENIED%'",
            ),
        ).isGreaterThanOrEqualTo(3)
    }

    @Test
    fun `same idempotency key with different body conflicts and stores neither raw key nor authorization`() {
        val key = issueClient(setOf(IntegrationScope.TICKETS_CREATE))
        val first = perform(post("/api/v1/platform/tickets"), key, "reuse-key-001", customerRequest("first"))
        assertThat(first.response.status).isEqualTo(201)
        val second = perform(post("/api/v1/platform/tickets"), key, "reuse-key-001", customerRequest("second"))
        assertThat(second.response.status).isEqualTo(409)
        assertThat(objectMapper.readTree(second.response.contentAsString).get("type").asText())
            .isEqualTo("/problems/idempotency-key-reused")
        assertThat(count("tickets")).isEqualTo(1)

        val stored = jdbcTemplate.queryForList(
            "select idempotency_key_hash, request_hash, response_headers_json, response_body_json from platform_idempotency_records",
        ).joinToString()
        assertThat(stored).doesNotContain("reuse-key-001").doesNotContain(key).doesNotContain(key.substringAfter('.'))
        val audits = jdbcTemplate.queryForList("select metadata_json from admin_security_audit_events", String::class.java)
            .joinToString()
        assertThat(audits).doesNotContain("reuse-key-001").doesNotContain(key).doesNotContain(key.substringAfter('.'))
    }

    @Test
    fun `stale If-Match returns structured precondition failure without a second audit`() {
        val key = issueClient(setOf(IntegrationScope.TICKETS_CREATE, IntegrationScope.TICKETS_UPDATE))
        val created = perform(post("/api/v1/platform/tickets"), key, "etag-create", customerRequest("etag"))
        val ticketNumber = objectMapper.readTree(created.response.contentAsString).get("ticketNumber").asLong()
        val update = perform(
            patch("/api/v1/platform/tickets/{ticketNumber}", ticketNumber).header("If-Match", "\"ticket-v0\""),
            key,
            "etag-update-1",
            """{"status":"OPEN"}""",
        )
        assertThat(update.response.status).isEqualTo(200)
        val stale = perform(
            patch("/api/v1/platform/tickets/{ticketNumber}", ticketNumber).header("If-Match", "\"ticket-v0\""),
            key,
            "etag-update-2",
            """{"priority":"HIGH"}""",
        )
        assertThat(stale.response.status).isEqualTo(412)
        assertThat(stale.response.getHeader("ETag")).isEqualTo("\"ticket-v1\"")
        assertThat(objectMapper.readTree(stale.response.contentAsString).get("currentVersion").asLong()).isEqualTo(1)
        assertThat(count("ticket_audits")).isEqualTo(2)
    }

    @Test
    fun `concurrent same-key create converges to one ticket and one change audit`() {
        val key = issueClient(setOf(IntegrationScope.TICKETS_CREATE))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                Callable {
                    perform(post("/api/v1/platform/tickets"), key, "race-create-1", customerRequest("race"))
                        .response
                }
            }
            val responses = executor.invokeAll(tasks).map { it.get() }
            assertThat(responses.map { it.status }).containsOnly(201)
            assertThat(responses.map { it.contentAsString }.distinct()).hasSize(1)
            assertThat(count("tickets")).isEqualTo(1)
            assertThat(count("ticket_audits")).isEqualTo(1)
            assertThat(count("platform_idempotency_records")).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `public peer forwarded spoof and expired credential fail before ticket data`() {
        val key = issueClient(setOf(IntegrationScope.TICKETS_READ))
        val spoof = mockMvc.perform(
            authorized(get("/api/v1/platform/tickets/999999"), key)
                .header("X-Forwarded-For", "10.1.2.3")
                .with { it.remoteAddr = "203.0.113.9"; it },
        ).andReturn()
        assertThat(spoof.response.status).isEqualTo(403)
        assertThat(objectMapper.readTree(spoof.response.contentAsString).get("type").asText())
            .isEqualTo("/problems/platform-network-denied")

        jdbcTemplate.update(
            "update integration_credentials set created_at = now() - interval '2 days', expires_at = now() - interval '1 second'",
        )
        val expired = mockMvc.perform(authorized(get("/api/v1/platform/tickets/999999"), key)).andReturn()
        assertThat(expired.response.status).isEqualTo(401)
        assertThat(objectMapper.readTree(expired.response.contentAsString).get("type").asText())
            .isEqualTo("/problems/platform-authentication-failed")

        jdbcTemplate.update(
            """
            update integration_credentials
            set expires_at = now() + interval '1 day', status = 'REVOKED', revoked_at = now(), overlap_expires_at = null
            """.trimIndent(),
        )
        val revoked = mockMvc.perform(authorized(get("/api/v1/platform/tickets/999999"), key)).andReturn()
        assertThat(revoked.response.status).isEqualTo(401)
        val revokedProblem = objectMapper.readTree(revoked.response.contentAsString)
        assertThat(revokedProblem.get("type").asText()).isEqualTo("/problems/platform-authentication-failed")
        assertThat(revokedProblem.get("detail").asText())
            .isEqualTo(objectMapper.readTree(expired.response.contentAsString).get("detail").asText())
    }

    @Test
    fun `public follow-up and admin surfaces do not exist under Platform v1`() {
        val key = issueClient(IntegrationScope.entries.toSet())
        val publicComment = mockMvc.perform(
            authorized(post("/api/v1/platform/tickets/1000/public-comments"), key)
                .header("Idempotency-Key", "public-comment-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"body":"not allowed"}"""),
        ).andReturn()
        val admin = mockMvc.perform(authorized(get("/api/v1/platform/admin/integration-clients"), key)).andReturn()

        assertThat(publicComment.response.status).isEqualTo(404)
        assertThat(admin.response.status).isEqualTo(404)
        assertThat(count("ticket_comments")).isZero()
    }

    private fun issueClient(
        scopes: Set<IntegrationScope>,
        constraints: IntegrationResourceConstraints = IntegrationResourceConstraints(),
    ): String {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            "admin",
            null,
            listOf(SimpleGrantedAuthority("integration:clients:manage")),
        )
        return try {
            administration.create(
                CreateIntegrationClientCommand(
                    "client-${UUID.randomUUID()}",
                    "Platform test client",
                    scopes,
                    constraints,
                    Instant.now().plus(1, ChronoUnit.DAYS),
                ),
                IntegrationAdminActor(
                    adminId,
                    "Admin",
                    RequestSource.ADMIN_UI,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                ),
            ).apiKey
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun perform(
        builder: MockHttpServletRequestBuilder,
        key: String,
        idempotencyKey: String,
        body: String,
    ) = mockMvc.perform(
        authorized(builder, key)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body),
    ).andReturn()

    private fun authorized(builder: MockHttpServletRequestBuilder, key: String) =
        builder.header("Authorization", "Bearer $key").header("X-Actor-Id", UUID.randomUUID().toString())

    private fun customerRequest(message: String) =
        """{"kind":"CUSTOMER_REQUEST","subject":"Need help","message":"$message","requester":{"name":"Customer","email":"customer@example.com"}}"""

    private fun count(table: String): Long = jdbcTemplate.queryForObject("select count(*) from $table", Long::class.java)!!

    private fun countWhere(table: String, condition: String): Long =
        jdbcTemplate.queryForObject("select count(*) from $table where $condition", Long::class.java)!!

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
