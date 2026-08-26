package dev.deskseed.sla.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.sla.FirstReplyPolicyConditions
import dev.deskseed.sla.FirstReplySlaAdministration
import dev.deskseed.sla.FirstReplySlaAnalytics
import dev.deskseed.sla.FirstReplySlaBreachScanner
import dev.deskseed.sla.FirstReplySlaScanResult
import dev.deskseed.sla.FirstReplySlaProjectionRebuilder
import dev.deskseed.sla.FirstReplySlaTicketSample
import dev.deskseed.sla.FirstReplySlaPolicyDefinition
import dev.deskseed.sla.SlaAdminActor
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.StaffTicketCommandActor
import dev.deskseed.ticketing.SubmitPublicRequestCommand
import dev.deskseed.ticketing.TicketField
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketSubmitted
import dev.deskseed.ticketing.TicketingFacade
import dev.deskseed.ticketing.UpdateAgentTicketCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Callable

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@dev.deskseed.testsupport.category.SlowTest
class FirstReplySlaIntegrationTest {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var administration: FirstReplySlaAdministration
    @Autowired private lateinit var analytics: FirstReplySlaAnalytics
    @Autowired private lateinit var ticketing: TicketingFacade
    @Autowired private lateinit var commands: AgentTicketCommandService
    @Autowired private lateinit var scanner: FirstReplySlaBreachScanner
    @Autowired private lateinit var projectionRebuilder: FirstReplySlaProjectionRebuilder
    @Autowired private lateinit var lifecycleProjection: FirstReplySlaLifecycleProjection

    private lateinit var adminId: UUID
    private lateinit var customerId: UUID

    @BeforeEach
    fun seed() {
        jdbc.execute(
            """
            truncate table
                sla_target_events, analytics_first_reply_facts, sla_target_instances,
                ticket_state_intervals, sla_policy_activations, sla_policy_pause_statuses,
                sla_policy_priority_targets, sla_policy_versions, sla_policies,
                ticket_audit_events, ticket_audits, ticket_comments, request_access_tokens,
                tickets, customers, group_memberships, support_groups, staff_authority_grants,
                admin_security_audit_events, staff_login_throttles
            restart identity cascade
            """.trimIndent(),
        )
        jdbc.execute(
            "truncate table customer_registration_intent_consents, customer_registration_intents, " +
                "customer_consent_acceptances, customer_consent_policy_versions, customer_consent_policies cascade",
        )
        jdbc.update("delete from staff_accounts")
        jdbc.update(
            """
            update sla_breach_scan_state set lease_owner = null, lease_until = null,
                last_started_at = null, last_completed_at = null, last_target_due_at = null,
                last_target_id = null, last_claimed_count = 0, last_breached_count = 0 where id = 1
            """.trimIndent(),
        )
        adminId = UUID.randomUUID()
        customerId = UUID.randomUUID()
        jdbc.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status, password_hash, created_at, updated_at)
            values (?, 'sla-admin@example.com', 'sla-admin@example.com', 'SLA 관리자', 'ADMIN', 'ACTIVE',
                    'fixture-hash', now(), now())
            """.trimIndent(),
            adminId,
        )
        jdbc.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, 'SLA 고객', ?, ?, now(), now())
            """.trimIndent(),
            customerId,
            "sla-${UUID.randomUUID()}@example.com",
            "sla@example.com",
        )
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `activation snapshots policy and schedule and public human reply achieves while internal note does not`() {
        val policy = activatePolicy()
        val urgentPreview = administration.preview(
            null,
            null,
            FirstReplySlaTicketSample(TicketPriority.URGENT, null, TicketChannel.WEB),
            Instant.parse("2026-08-17T00:00:00Z"),
        )
        val submitted = submitRequest()

        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACTIVE")
        assertThat(value("select policy_version from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(1)
        assertThat(value("select schedule_version from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(1)

        val afterInternal = updateComment(submitted.ticketNumber, 0, CommentVisibility.INTERNAL)
        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACTIVE")
        updateComment(submitted.ticketNumber, afterInternal.version, CommentVisibility.PUBLIC)

        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACHIEVED")
        assertThat(value("select outcome from analytics_first_reply_facts where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACHIEVED")
        assertThat(value("select due_at is not null from analytics_first_reply_facts where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(true)
        assertThat(count("select count(*) from sla_target_events where event_type = 'SLA_TARGET_ACHIEVED'"))
            .isEqualTo(1)
        assertThat(policy.active).isTrue()
        assertThat(urgentPreview.targetMinutes).isEqualTo(15)
        assertThat(count("select count(*) from admin_security_audit_events where event_type = 'SLA_POLICY_ACTIVATED'"))
            .isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `policy edit and activation never rewrite an existing target snapshot`() {
        val activeV1 = activatePolicy()
        val submitted = submitRequest()
        val v2 = administration.createVersion(
            activeV1.id,
            activeV1.aggregateVersion,
            FirstReplySlaPolicyDefinition(
                name = "변경된 First Reply",
                position = 5,
                scheduleId = DEFAULT_SCHEDULE_ID,
                conditions = FirstReplyPolicyConditions(),
                targets = mapOf(TicketPriority.NORMAL to 30, TicketPriority.URGENT to 10),
                pauseStatuses = setOf(TicketStatus.PENDING),
            ),
            adminActor(),
        )
        administration.activate(v2.id, v2.version, v2.aggregateVersion, adminActor())

        assertThat(value("select policy_version from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(1)
        assertThat(value("select schedule_version from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(1)
        assertThat(value("select target_minutes from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(60)
        assertThatThrownBySnapshotRewrite(submitted.ticketId)
    }

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `replayed submission does not rematch policy or append a duplicate target event`() {
        val activeV1 = activatePolicy()
        val submitted = submitRequest()
        val v2 = administration.createVersion(
            activeV1.id,
            activeV1.aggregateVersion,
            FirstReplySlaPolicyDefinition(
                name = "Replay must not rematch",
                position = 1,
                scheduleId = DEFAULT_SCHEDULE_ID,
                conditions = FirstReplyPolicyConditions(),
                targets = mapOf(TicketPriority.NORMAL to 10),
                pauseStatuses = setOf(TicketStatus.PENDING),
            ),
            adminActor(),
        )
        administration.activate(v2.id, v2.version, v2.aggregateVersion, adminActor())

        val createdAt = jdbc.queryForObject(
            "select created_at from tickets where id = ?",
            java.time.OffsetDateTime::class.java,
            submitted.ticketId,
        )!!.toInstant()
        val auditId = jdbc.queryForObject(
            "select id from ticket_audits where ticket_id = ? order by created_at limit 1",
            UUID::class.java,
            submitted.ticketId,
        )!!
        lifecycleProjection.onTicketSubmitted(
            TicketSubmitted(
                ticketId = submitted.ticketId,
                ticketNumber = submitted.ticketNumber,
                requesterId = customerId,
                kind = TicketKind.CUSTOMER_REQUEST,
                priority = TicketPriority.NORMAL,
                groupId = null,
                channel = TicketChannel.WEB,
                status = TicketStatus.NEW,
                ticketAuditId = auditId,
                actorType = "CUSTOMER",
                actorId = customerId,
                source = "CUSTOMER_PORTAL",
                requestId = "replayed-request",
                correlationId = "replayed-correlation",
                startsFirstReplySla = true,
                occurredAt = createdAt,
            ),
        )

        assertThat(value("select policy_version from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(1)
        assertThat(count("select count(*) from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(1)
        assertThat(count("select count(*) from sla_target_events where target_id = (select id from sla_target_instances where ticket_id = '${submitted.ticketId}')"))
            .isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `active policies match by ascending position before creation order`() {
        val later = createAndActivatePolicy("낮은 우선순위 정책", 20, 60)
        val earlier = createAndActivatePolicy("높은 우선순위 정책", 10, 30)

        val matched = administration.preview(
            null,
            null,
            FirstReplySlaTicketSample(TicketPriority.NORMAL, null, TicketChannel.WEB),
            Instant.parse("2026-08-17T00:00:00Z"),
        )

        assertThat(matched.policyId).isEqualTo(earlier.id)
        assertThat(matched.policyId).isNotEqualTo(later.id)
        assertThat(matched.targetMinutes).isEqualTo(30)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `pending pauses and resumes without changing immutable target snapshots`() {
        activatePolicy()
        val submitted = submitRequest()

        val pending = updateStatus(submitted.ticketNumber, 0, TicketStatus.PENDING)
        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("PAUSED")
        assertThat(value("select due_at is null from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(true)

        updateStatus(submitted.ticketNumber, pending.version, TicketStatus.OPEN)
        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACTIVE")
        assertThat(value("select due_at is not null from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(true)
        assertThat(count("select count(*) from ticket_state_intervals where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(3)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `public human reply while pending achieves and persists the paused clock shape`() {
        activatePolicy()
        val submitted = submitRequest()
        val pending = updateStatus(submitted.ticketNumber, 0, TicketStatus.PENDING)

        updateComment(submitted.ticketNumber, pending.version, CommentVisibility.PUBLIC)

        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACHIEVED")
        assertThat(value("select due_at is null from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(true)
        assertThat(value("select outcome from analytics_first_reply_facts where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("ACHIEVED")
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `canonical status audits rebuild pause intervals and target clock idempotently`() {
        activatePolicy()
        val submitted = submitRequest()
        updateStatus(submitted.ticketNumber, 0, TicketStatus.PENDING)

        jdbc.update("delete from ticket_state_intervals where ticket_id = ?", submitted.ticketId)
        jdbc.update(
            """
            update sla_target_instances
               set state = 'ACTIVE', active_segment_started_at = started_at,
                   due_at = started_at + interval '60 minutes', remaining_business_minutes = target_minutes
             where ticket_id = ?
            """.trimIndent(),
            submitted.ticketId,
        )
        jdbc.update(
            "update analytics_first_reply_facts set outcome = 'ACTIVE', due_at = started_at + interval '60 minutes' where ticket_id = ?",
            submitted.ticketId,
        )

        val first = projectionRebuilder.rebuild(submitted.ticketId)
        val firstProjection = intervalProjection(submitted.ticketId)
        val second = projectionRebuilder.rebuild(submitted.ticketId)

        assertThat(first.intervalCount).isEqualTo(2)
        assertThat(first.targetRecalculated).isTrue()
        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("PAUSED")
        assertThat(value("select outcome from analytics_first_reply_facts where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("PAUSED")
        assertThat(second.targetRecalculated).isFalse()
        assertThat(intervalProjection(submitted.ticketId)).isEqualTo(firstProjection)
    }

    @Test
    fun `no active target produces NO_POLICY and scanner workers converge exactly once after restart`() {
        val noPolicy = submitRequest()
        assertThat(value("select outcome from analytics_first_reply_facts where ticket_id = '${noPolicy.ticketId}'"))
            .isEqualTo("NO_POLICY")
        assertThat(count("select count(*) from sla_target_instances where ticket_id = '${noPolicy.ticketId}'"))
            .isZero()
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `concurrent breach scans and restart materialize one terminal event`() {
        activatePolicy()
        val submitted = submitRequest()
        jdbc.update(
            "update sla_target_instances set due_at = now() - interval '1 minute' where ticket_id = ?",
            submitted.ticketId,
        )
        jdbc.update(
            "update analytics_first_reply_facts set due_at = now() - interval '1 minute' where ticket_id = ?",
            submitted.ticketId,
        )

        val pool = Executors.newFixedThreadPool(2)
        try {
            val results: List<FirstReplySlaScanResult> = listOf(
                pool.submit(Callable { scanner.scan("worker-a", 10) }),
                pool.submit(Callable { scanner.scan("worker-b", 10) }),
            ).map { it.get() }
            assertThat(results.sumOf { it.breached }).isEqualTo(1)
        } finally {
            pool.shutdownNow()
        }
        assertThat(scanner.scan("worker-restart", 10).breached).isZero()
        assertThat(count("select count(*) from sla_target_events where event_type = 'SLA_TARGET_BREACHED'"))
            .isEqualTo(1)
        assertThat(value("select state from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo("BREACHED")
        assertThat(value("select due_at is not null from sla_target_instances where ticket_id = '${submitted.ticketId}'"))
            .isEqualTo(true)
    }

    @Test
    fun `scanner owner leaves room for the correlation prefix`() {
        assertThat(scanner.scan("a".repeat(91), 1).claimed).isZero()
        org.assertj.core.api.Assertions.assertThatThrownBy {
            scanner.scan("a".repeat(92), 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `analytics reconciles achieved and breached target facts with policy and priority filters`() {
        val policy = activatePolicy()
        val achievedTicket = submitRequest()
        val breachedTicket = submitRequest()
        updateComment(achievedTicket.ticketNumber, 0, CommentVisibility.PUBLIC)
        jdbc.update(
            "update sla_target_instances set due_at = now() - interval '1 minute' where ticket_id = ?",
            breachedTicket.ticketId,
        )
        jdbc.update(
            "update analytics_first_reply_facts set due_at = now() - interval '1 minute' where ticket_id = ?",
            breachedTicket.ticketId,
        )
        scanner.scan("analytics-worker", 10)

        val result = analytics.summary(policy.id, TicketPriority.NORMAL)
        assertThat(result.achieved).isEqualTo(1)
        assertThat(result.breached).isEqualTo(1)
        assertThat(result.noPolicy).isZero()
        assertThat(result.achievedRateDenominator).isEqualTo(2)
        assertThat(result.achievedRate).isEqualTo(0.5)
        assertThat(count(
            "select count(*) from analytics_first_reply_facts where policy_id = '${policy.id}' and outcome in ('ACHIEVED', 'BREACHED')",
        )).isEqualTo(result.achievedRateDenominator.toInt())
    }

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = ["ADMIN"])
    fun `scanner analytics and ticket projection queries have bounded index access paths`() {
        val policy = activatePolicy()
        val submitted = submitRequest()
        jdbc.execute("set local enable_seqscan = off")

        val scannerPlan = explain(
            "select id, due_at from sla_target_instances where state = 'ACTIVE' and due_at <= now() order by due_at, id limit 100",
        )
        val analyticsPlan = explain(
            "select outcome, count(*) from analytics_first_reply_facts where policy_id = '${policy.id}' group by outcome",
        )
        val ticketPlan = explain(
            "select t.id, fact.outcome from tickets t left join analytics_first_reply_facts fact on fact.ticket_id = t.id where t.id = '${submitted.ticketId}'",
        )

        assertThat(scannerPlan).contains("sla_target_instances_breach_scan_idx")
        assertThat(analyticsPlan).contains("analytics_first_reply_facts_policy_idx")
        assertThat(ticketPlan).contains("analytics_first_reply_facts_pkey")
    }

    private fun activatePolicy() = administration.create(
        FirstReplySlaPolicyDefinition(
            name = "기본 First Reply",
            position = 10,
            scheduleId = DEFAULT_SCHEDULE_ID,
            conditions = FirstReplyPolicyConditions(),
            targets = mapOf(TicketPriority.NORMAL to 60, TicketPriority.URGENT to 15),
            pauseStatuses = setOf(TicketStatus.PENDING),
        ),
        adminActor(),
    ).let { administration.activate(it.id, it.version, it.aggregateVersion, adminActor()) }

    private fun createAndActivatePolicy(name: String, position: Int, targetMinutes: Long) = administration.create(
        FirstReplySlaPolicyDefinition(
            name = name,
            position = position,
            scheduleId = DEFAULT_SCHEDULE_ID,
            conditions = FirstReplyPolicyConditions(),
            targets = mapOf(TicketPriority.NORMAL to targetMinutes),
            pauseStatuses = setOf(TicketStatus.PENDING),
        ),
        adminActor(),
    ).let { administration.activate(it.id, it.version, it.aggregateVersion, adminActor()) }

    private fun submitRequest() = ticketing.submitPublicRequest(
        SubmitPublicRequestCommand(
            requesterId = customerId,
            subject = "First Reply SLA fixture",
            message = "고객의 첫 공개 문의",
            actor = ActorRef(ActorType.CUSTOMER, customerId),
            context = context(RequestSource.CUSTOMER_PORTAL),
        ),
    )

    private fun updateComment(ticketNumber: Long, version: Long, visibility: CommentVisibility) = commands.update(
        UpdateAgentTicketCommand(
            ticketNumber = ticketNumber,
            expectedVersion = version,
            changedFields = emptySet(),
            status = null,
            priority = null,
            groupId = null,
            assigneeId = null,
            comment = AgentCommentDraft(visibility, "상담원 응답"),
            actor = StaffTicketCommandActor(adminId, "SLA 관리자", true),
            context = context(RequestSource.AGENT_UI),
        ),
    )

    private fun updateStatus(ticketNumber: Long, version: Long, status: TicketStatus) = commands.update(
        UpdateAgentTicketCommand(
            ticketNumber = ticketNumber,
            expectedVersion = version,
            changedFields = setOf(TicketField.STATUS),
            status = status,
            priority = null,
            groupId = null,
            assigneeId = null,
            comment = null,
            actor = StaffTicketCommandActor(adminId, "SLA 관리자", true),
            context = context(RequestSource.AGENT_UI),
        ),
    )

    private fun adminActor() = SlaAdminActor(
        adminId,
        "SLA 관리자",
        RequestSource.ADMIN_UI,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
    )

    private fun context(source: RequestSource) = CommandContext(
        source,
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
    )

    private fun value(sql: String): Any? = jdbc.queryForMap(sql).values.single()
    private fun count(sql: String): Int = jdbc.queryForObject(sql, Int::class.java)!!

    private fun assertThatThrownBySnapshotRewrite(ticketId: UUID) {
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbc.update("update sla_target_instances set target_minutes = 30 where ticket_id = ?", ticketId)
        }.hasMessageContaining("First Reply SLA target snapshot is immutable")
    }

    private fun intervalProjection(ticketId: UUID): List<Map<String, Any?>> = jdbc.queryForList(
        """
        select status, started_at, ended_at, start_audit_id, end_audit_id
          from ticket_state_intervals where ticket_id = ? order by started_at, status
        """.trimIndent(),
        ticketId,
    )

    private fun explain(sql: String): String = jdbc.queryForList("explain (costs off) $sql", String::class.java)
        .joinToString("\n")

    companion object {
        private val DEFAULT_SCHEDULE_ID: UUID = UUID.fromString("51000000-0000-0000-0000-000000000001")
    }
}
