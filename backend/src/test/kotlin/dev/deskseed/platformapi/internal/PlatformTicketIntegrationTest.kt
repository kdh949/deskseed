package dev.deskseed.platformapi.internal

import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.CreateIntegrationClientCommand
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientAdministration
import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationScope
import dev.deskseed.integration.IntegrationTicketField
import dev.deskseed.integration.IntegrationTicketKind
import dev.deskseed.sla.FirstReplyPolicyConditions
import dev.deskseed.sla.FirstReplySlaAdministration
import dev.deskseed.sla.FirstReplySlaPolicyDefinition
import dev.deskseed.sla.SlaAdminActor
import dev.deskseed.ticketing.TicketingFacade
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.StaffTicketReadScope
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.ticketing.StaffTicketSearchFilter
import io.micrometer.core.instrument.MeterRegistry
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
        "deskseed.platform.idempotency.cleanup-batch-size=2",
        "deskseed.platform.idempotency.in-progress-grace=1h",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class PlatformTicketIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var administration: IntegrationClientAdministration
    @Autowired private lateinit var ticketingFacade: TicketingFacade
    @Autowired private lateinit var staffTicketReadStore: StaffTicketReadStore
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var idempotencyRetentionJob: PlatformIdempotencyRetentionJob
    @Autowired private lateinit var meterRegistry: MeterRegistry
    @Autowired private lateinit var slaAdministration: FirstReplySlaAdministration

    private lateinit var adminId: UUID

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute(
            "truncate table platform_idempotency_records, integration_credentials, integration_clients, " +
                "access_audit_events, admin_security_audit_events, ticket_audit_events, ticket_audits, " +
                "ticket_comments, ticket_state_intervals, sla_target_events, analytics_first_reply_facts, " +
                "sla_target_instances, sla_policy_activations, sla_policy_pause_statuses, " +
                "sla_policy_priority_targets, sla_policy_versions, sla_policies, tickets, group_memberships, " +
                "support_groups, staff_authority_grants, " +
                "customers cascade",
        )
        jdbcTemplate.update("delete from staff_accounts")
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

        val ticketNumber = objectMapper.readTree(result.response.contentAsString).get("ticketNumber").asLong()
        val staffDetail = staffTicketReadStore.findDetail(ticketNumber)!!
        assertThat(staffDetail.customer).isNull()
        assertThat(staffDetail.ticket.requester.type).isEqualTo("INTEGRATION_CLIENT")
        assertThat(staffDetail.comments.single().source).isEqualTo("PLATFORM_API")
        assertThat(
            staffTicketReadStore.search(
                "Reconcile",
                StaffTicketReadScope.ALL_TICKETS,
                adminId,
                StaffTicketSearchFilter(),
                10,
            ).items.map { it.ticketNumber },
        ).containsExactly(ticketNumber)
    }

    @Test
    fun `platform customer request starts First Reply once while internal work item stays outside the metric`() {
        val policy = activateApiFirstReplyPolicy()
        val key = issueClient(setOf(IntegrationScope.TICKETS_CREATE))
        val created = perform(post("/api/v1/platform/tickets"), key, "sla-customer-create", customerRequest("SLA start"))
        val ticketNumber = objectMapper.readTree(created.response.contentAsString).get("ticketNumber").asLong()
        val replay = perform(post("/api/v1/platform/tickets"), key, "sla-customer-create", customerRequest("SLA start"))

        assertThat(created.response.status).isEqualTo(201)
        assertThat(replay.response.status).isEqualTo(201)
        assertThat(replay.response.contentAsString).isEqualTo(created.response.contentAsString)

        val ticketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            ticketNumber,
        )!!
        val creationAuditId = jdbcTemplate.queryForObject(
            "select id from ticket_audits where ticket_id = ?",
            UUID::class.java,
            ticketId,
        )!!
        assertThat(countWhere("ticket_audits", "ticket_id = '$ticketId'")).isEqualTo(1)
        assertThat(countWhere("ticket_state_intervals", "ticket_id = '$ticketId'")).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select start_audit_id from ticket_state_intervals where ticket_id = ?",
                UUID::class.java,
                ticketId,
            ),
        ).isEqualTo(creationAuditId)
        assertThat(countWhere("sla_target_instances", "ticket_id = '$ticketId' and metric = 'FIRST_REPLY'"))
            .isEqualTo(1)
        assertThat(countWhere("analytics_first_reply_facts", "ticket_id = '$ticketId'"))
            .isEqualTo(1)
        assertThat(countWhere("sla_target_events", "ticket_audit_id = '$creationAuditId' and event_type = 'SLA_TARGET_STARTED'"))
            .isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select policy_id from sla_target_instances where ticket_id = ?",
                UUID::class.java,
                ticketId,
            ),
        ).isEqualTo(policy.id)
        assertThat(
            jdbcTemplate.queryForObject(
                "select source from sla_target_events where ticket_audit_id = ?",
                String::class.java,
                creationAuditId,
            ),
        ).isEqualTo(RequestSource.PLATFORM_API.name)
        assertThat(
            jdbcTemplate.queryForObject(
                "select actor_type from sla_target_events where ticket_audit_id = ?",
                String::class.java,
                creationAuditId,
            ),
        ).isEqualTo("INTEGRATION_CLIENT")

        val internal = perform(
            post("/api/v1/platform/tickets"),
            key,
            "sla-internal-create",
            """{"kind":"INTERNAL_WORK_ITEM","subject":"Internal SLA exclusion","message":"Investigate reconciliation"}""",
        )
        val internalTicketNumber = objectMapper.readTree(internal.response.contentAsString).get("ticketNumber").asLong()
        val internalTicketId = jdbcTemplate.queryForObject(
            "select id from tickets where ticket_number = ?",
            UUID::class.java,
            internalTicketNumber,
        )!!
        val internalCreationAuditId = jdbcTemplate.queryForObject(
            "select id from ticket_audits where ticket_id = ?",
            UUID::class.java,
            internalTicketId,
        )!!
        assertThat(internal.response.status).isEqualTo(201)
        assertThat(countWhere("ticket_state_intervals", "ticket_id = '$internalTicketId'")).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select start_audit_id from ticket_state_intervals where ticket_id = ?",
                UUID::class.java,
                internalTicketId,
            ),
        ).isEqualTo(internalCreationAuditId)
        assertThat(countWhere("sla_target_instances", "ticket_id = '$internalTicketId' and metric = 'FIRST_REPLY'"))
            .isZero()
        assertThat(countWhere("analytics_first_reply_facts", "ticket_id = '$internalTicketId'"))
            .isZero()
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
    fun `deterministic create assignment failure is replayed after organization changes without orphans`() {
        val key = issueClient(setOf(IntegrationScope.TICKETS_CREATE))
        val groupId = UUID.randomUUID()
        val body =
            """{"kind":"CUSTOMER_REQUEST","subject":"Invalid assignment","message":"No orphan","requester":{"name":"Customer","email":"customer@example.com"},"groupId":"$groupId","assigneeId":"$adminId"}"""

        val invalid = perform(post("/api/v1/platform/tickets"), key, "invalid-create-0001", body)
        assertThat(invalid.response.status).isEqualTo(400)
        assertThat(objectMapper.readTree(invalid.response.contentAsString).get("code").asText())
            .isEqualTo("GROUP_NOT_ACTIVE")
        assertThat(
            jdbcTemplate.queryForObject(
                "select status from platform_idempotency_records",
                String::class.java,
            ),
        ).isEqualTo("FAILED_FINAL")
        assertThat(count("customers")).isZero()
        assertThat(count("tickets")).isZero()
        assertThat(count("ticket_comments")).isZero()
        assertThat(count("ticket_audits")).isZero()

        jdbcTemplate.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, 'Recovered', 'ACTIVE', now(), now(), 0)",
            groupId,
        )
        jdbcTemplate.update(
            "insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version) values (?, ?, ?, 'ACTIVE', now(), now(), 0)",
            UUID.randomUUID(),
            groupId,
            adminId,
        )

        val replay = perform(post("/api/v1/platform/tickets"), key, "invalid-create-0001", body)
        assertThat(replay.response.status).isEqualTo(400)
        assertThat(replay.response.contentAsString).isEqualTo(invalid.response.contentAsString)
        assertThat(count("customers")).isZero()
        assertThat(count("tickets")).isZero()
        assertThat(count("ticket_comments")).isZero()
        assertThat(count("ticket_audits")).isZero()

        val memberGroupId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into support_groups (id, name, status, created_at, updated_at, version) values (?, 'Member check', 'ACTIVE', now(), now(), 0)",
            memberGroupId,
        )
        val memberBody =
            """{"kind":"CUSTOMER_REQUEST","subject":"Invalid member","message":"No orphan","requester":{"name":"Customer","email":"customer@example.com"},"groupId":"$memberGroupId","assigneeId":"$adminId"}"""
        val invalidMember = perform(post("/api/v1/platform/tickets"), key, "invalid-create-0002", memberBody)
        assertThat(invalidMember.response.status).isEqualTo(400)
        assertThat(objectMapper.readTree(invalidMember.response.contentAsString).get("code").asText())
            .isEqualTo("ASSIGNEE_NOT_ACTIVE_GROUP_MEMBER")
        jdbcTemplate.update(
            "insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version) values (?, ?, ?, 'ACTIVE', now(), now(), 0)",
            UUID.randomUUID(),
            memberGroupId,
            adminId,
        )
        val memberReplay = perform(post("/api/v1/platform/tickets"), key, "invalid-create-0002", memberBody)
        assertThat(memberReplay.response.status).isEqualTo(400)
        assertThat(memberReplay.response.contentAsString).isEqualTo(invalidMember.response.contentAsString)
        assertThat(count("customers")).isZero()
        assertThat(count("tickets")).isZero()
        assertThat(count("ticket_comments")).isZero()
        assertThat(count("ticket_audits")).isZero()
        assertThat(count("platform_idempotency_records")).isEqualTo(2)
    }

    @Test
    fun `idempotency retention deletes bounded expired rows and preserves active and recent in progress rows`() {
        issueClient(setOf(IntegrationScope.TICKETS_READ))
        val clientId = jdbcTemplate.queryForObject("select id from integration_clients", UUID::class.java)!!
        val now = Instant.parse("2026-08-13T00:00:00Z")
        val deletedBefore = meterRegistry.counter("deskseed.platform.idempotency.cleanup.deleted").count()

        insertIdempotencyRecord(clientId, "expired-success", "SUCCEEDED", now.minus(2, ChronoUnit.HOURS), 200)
        insertIdempotencyRecord(clientId, "expired-failure", "FAILED_FINAL", now.minus(90, ChronoUnit.MINUTES), 400)
        insertIdempotencyRecord(clientId, "stale-progress", "IN_PROGRESS", now.minus(2, ChronoUnit.HOURS), null)
        insertIdempotencyRecord(clientId, "recent-progress", "IN_PROGRESS", now.minus(30, ChronoUnit.MINUTES), null)
        insertIdempotencyRecord(clientId, "active-success", "SUCCEEDED", now.plus(1, ChronoUnit.HOURS), 200)

        val first = idempotencyRetentionJob.purgeExpired(now)
        assertThat(first.deletedCount).isEqualTo(2)
        val second = idempotencyRetentionJob.purgeExpired(now)
        assertThat(second.deletedCount).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForList(
                "select operation_id from platform_idempotency_records order by operation_id",
                String::class.java,
            ),
        ).containsExactly("active-success", "recent-progress")
        assertThat(meterRegistry.counter("deskseed.platform.idempotency.cleanup.deleted").count() - deletedBefore)
            .isEqualTo(3.0)
        assertThat(meterRegistry.get("deskseed.platform.idempotency.cleanup.backlog.age").gauge().value())
            .isGreaterThanOrEqualTo(1_800.0)
        assertThat(meterRegistry.counter("deskseed.platform.idempotency.cleanup.failures").count())
            .isGreaterThanOrEqualTo(0.0)
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
        val staleReplay = perform(
            patch("/api/v1/platform/tickets/{ticketNumber}", ticketNumber).header("If-Match", "\"ticket-v0\""),
            key,
            "etag-update-2",
            """{"priority":"HIGH"}""",
        )
        assertThat(staleReplay.response.status).isEqualTo(412)
        assertThat(staleReplay.response.contentAsString).isEqualTo(stale.response.contentAsString)
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
            assertThat(count("ticket_state_intervals")).isEqualTo(1)
            assertThat(count("analytics_first_reply_facts")).isEqualTo(1)
            assertThat(count("platform_idempotency_records")).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `audit and response receipt failures roll back reservation and business mutation then retry converges`() {
        val key = issueClient(setOf(IntegrationScope.TICKETS_CREATE))
        jdbcTemplate.execute(
            """
            create or replace function fail_platform_ticket_audit() returns trigger language plpgsql as ${'$'}${'$'}
            begin raise exception 'injected ticket audit failure'; end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_platform_ticket_audit before insert on ticket_audits " +
                "for each row execute function fail_platform_ticket_audit()",
        )
        try {
            val failed = perform(post("/api/v1/platform/tickets"), key, "crash-audit-1", customerRequest("audit crash"))
            assertThat(failed.response.status).isEqualTo(503)
            assertThat(count("tickets")).isZero()
            assertThat(count("customers")).isZero()
            assertThat(count("platform_idempotency_records")).isZero()
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_platform_ticket_audit on ticket_audits")
            jdbcTemplate.execute("drop function if exists fail_platform_ticket_audit()")
        }
        val auditRetry = perform(post("/api/v1/platform/tickets"), key, "crash-audit-1", customerRequest("audit crash"))
        assertThat(auditRetry.response.status).isEqualTo(201)

        jdbcTemplate.execute(
            """
            create or replace function fail_platform_receipt() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                if new.status = 'SUCCEEDED' then raise exception 'injected receipt failure'; end if;
                return new;
            end; ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "create trigger fail_platform_receipt before update on platform_idempotency_records " +
                "for each row execute function fail_platform_receipt()",
        )
        try {
            val failed = perform(post("/api/v1/platform/tickets"), key, "crash-receipt-1", customerRequest("receipt crash"))
            assertThat(failed.response.status).isEqualTo(503)
            assertThat(countWhere("tickets", "subject = 'Need help'")).isEqualTo(1)
            assertThat(countWhere("platform_idempotency_records", "idempotency_key_hash is not null")).isEqualTo(1)
        } finally {
            jdbcTemplate.execute("drop trigger if exists fail_platform_receipt on platform_idempotency_records")
            jdbcTemplate.execute("drop function if exists fail_platform_receipt()")
        }
        val receiptRetry = perform(post("/api/v1/platform/tickets"), key, "crash-receipt-1", customerRequest("receipt crash"))
        assertThat(receiptRetry.response.status).isEqualTo(201)
        assertThat(count("tickets")).isEqualTo(2)
        assertThat(count("ticket_audits")).isEqualTo(2)
        assertThat(count("platform_idempotency_records")).isEqualTo(2)
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

    private fun activateApiFirstReplyPolicy() = asAdmin {
        slaAdministration.create(
            FirstReplySlaPolicyDefinition(
                name = "Platform API First Reply",
                position = 10,
                scheduleId = DEFAULT_SCHEDULE_ID,
                conditions = FirstReplyPolicyConditions(channel = TicketChannel.API),
                targets = mapOf(TicketPriority.NORMAL to 60),
                pauseStatuses = setOf(TicketStatus.PENDING),
            ),
            slaAdminActor(),
        ).let { created ->
            slaAdministration.activate(created.id, created.version, created.aggregateVersion, slaAdminActor())
        }
    }

    private fun slaAdminActor() = SlaAdminActor(
        adminId,
        "Admin",
        RequestSource.ADMIN_UI,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
    )

    private fun <T> asAdmin(action: () -> T): T {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            "admin",
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        return try {
            action()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun count(table: String): Long = jdbcTemplate.queryForObject("select count(*) from $table", Long::class.java)!!

    private fun countWhere(table: String, condition: String): Long =
        jdbcTemplate.queryForObject("select count(*) from $table where $condition", Long::class.java)!!

    private fun insertIdempotencyRecord(
        clientId: UUID,
        operationId: String,
        status: String,
        expiresAt: Instant,
        responseStatus: Int?,
    ) {
        val final = status != "IN_PROGRESS"
        jdbcTemplate.update(
            """
            insert into platform_idempotency_records
                (id, client_id, operation_id, idempotency_key_hash, request_hash, status,
                 response_status, response_headers_json, response_body_json, created_at, expires_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            clientId,
            operationId,
            sha256("key-$operationId"),
            sha256("request-$operationId"),
            status,
            responseStatus,
            "{}".takeIf { final },
            "{}".takeIf { final },
            java.sql.Timestamp.from(expiresAt.minus(1, ChronoUnit.DAYS)),
            java.sql.Timestamp.from(expiresAt),
        )
    }

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val DEFAULT_SCHEDULE_ID: UUID = UUID.fromString("51000000-0000-0000-0000-000000000001")

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
