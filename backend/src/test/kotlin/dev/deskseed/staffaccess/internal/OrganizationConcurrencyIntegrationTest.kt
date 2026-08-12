package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContext
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.AdminActorContext
import dev.deskseed.organization.OrganizationConflictException
import dev.deskseed.organization.OrganizationAdministration
import dev.deskseed.organization.StaffRole
import dev.deskseed.ticketing.AgentCommentDraft
import dev.deskseed.ticketing.AgentTicketCommandService
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.CreateAgentTicketCommand
import dev.deskseed.ticketing.StaffTicketCommandActor
import dev.deskseed.ticketing.TicketCommandResult
import dev.deskseed.ticketing.TicketPriority
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@Testcontainers
class OrganizationConcurrencyIntegrationTest {
    @Autowired
    private lateinit var administration: OrganizationAdministration

    @Autowired
    private lateinit var ticketCommands: AgentTicketCommandService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var dataSource: DataSource

    @BeforeEach
    fun clearState() {
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
    fun `reciprocal concurrent admin disable leaves exactly one active admin and one audit`() {
        val firstAdmin = insertStaff("first-admin@example.com", "ADMIN")
        val secondAdmin = insertStaff("second-admin@example.com", "ADMIN")

        withUpdateDelay("staff_accounts") {
            val results = concurrently(
                {
                    asAdmin(firstAdmin) {
                        administration.disableStaff(secondAdmin.id, firstAdmin.actor())
                    }
                },
                {
                    asAdmin(secondAdmin) {
                        administration.disableStaff(firstAdmin.id, secondAdmin.actor())
                    }
                },
            )

            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(results.filterNotNull().map(::rootCause))
                .allMatch { it is OrganizationConflictException }
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from staff_accounts where role = 'ADMIN' and status = 'ACTIVE'",
                Long::class.java,
            ),
        ).isEqualTo(1)
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from admin_security_audit_events where event_type = 'STAFF_DISABLED'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `concurrent add and group disable never leaves an active member in a disabled group`() {
        val admin = insertStaff("admin@example.com", "ADMIN")
        val agent = insertStaff("agent@example.com", "AGENT")
        val groupId = insertGroup("결제 지원")

        withUpdateDelay("support_groups") {
            val results = concurrently(
                {
                    asAdmin(admin) {
                        administration.addGroupMember(groupId, agent.id, admin.actor())
                    }
                },
                {
                    asAdmin(admin) {
                        administration.disableGroup(groupId, admin.actor())
                    }
                },
            )

            assertThat(results).anyMatch { it == null }
        }

        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from group_memberships membership
                join support_groups group_row on group_row.id = membership.group_id
                where membership.status = 'ACTIVE' and group_row.status = 'DISABLED'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `concurrent add and staff disable never leaves an active member for disabled staff`() {
        val admin = insertStaff("admin@example.com", "ADMIN")
        val agent = insertStaff("agent@example.com", "AGENT")
        val groupId = insertGroup("결제 지원")

        withUpdateDelay("staff_accounts") {
            val results = concurrently(
                {
                    asAdmin(admin) {
                        administration.addGroupMember(groupId, agent.id, admin.actor())
                    }
                },
                {
                    asAdmin(admin) {
                        administration.disableStaff(agent.id, admin.actor())
                    }
                },
            )

            assertThat(results).anyMatch { it == null }
        }

        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from group_memberships membership
                join staff_accounts staff on staff.id = membership.staff_id
                where membership.status = 'ACTIVE' and staff.status = 'DISABLED'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isZero()
    }

    @Test
    fun `concurrent membership activation records one actual add`() {
        val admin = insertStaff("admin@example.com", "ADMIN")
        val agent = insertStaff("agent@example.com", "AGENT")
        val groupId = insertGroup("결제 지원")
        insertMembership(groupId, agent.id, "INACTIVE")

        withUpdateDelay("group_memberships") {
            val results = concurrently(
                {
                    asAdmin(admin) {
                        administration.addGroupMember(groupId, agent.id, admin.actor())
                    }
                },
                {
                    asAdmin(admin) {
                        administration.addGroupMember(groupId, agent.id, admin.actor())
                    }
                },
            )

            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(results.filterNotNull().map(::rootCause))
                .allMatch { it is OrganizationConflictException }
        }

        assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from admin_security_audit_events
                where event_type = 'GROUP_MEMBERSHIP_CHANGED'
                  and metadata_json::jsonb ->> 'action' = 'ADDED'
                """.trimIndent(),
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `concurrent case-insensitive group renames return one stable conflict`() {
        val admin = insertStaff("admin@example.com", "ADMIN")
        val firstGroup = insertGroup("Support first")
        val secondGroup = insertGroup("Support second")

        withUpdateDelay("support_groups") {
            val results = concurrently(
                {
                    asAdmin(admin) {
                        administration.renameGroup(firstGroup, "Support", admin.actor())
                    }
                },
                {
                    asAdmin(admin) {
                        administration.renameGroup(secondGroup, " support ", admin.actor())
                    }
                },
            )

            assertThat(results.count { it == null }).isEqualTo(1)
            assertThat(results.filterNotNull().map(::rootCause))
                .allMatch { it is OrganizationConflictException }
        }
    }

    @Test
    fun `ticket assignment and group disable share one organization consistency lock`() {
        val admin = insertStaff("assignment-admin@example.com", "ADMIN")
        val agent = insertStaff("assignment-agent@example.com", "AGENT")
        val groupId = insertGroup("동시 배정 그룹")
        insertMembership(groupId, agent.id, "ACTIVE")
        val customerId = insertCustomer("assignment-customer@example.com")
        val executor = Executors.newFixedThreadPool(2)

        installBlockingTicketInsertTrigger()
        try {
            dataSource.connection.use { blocker ->
                blocker.prepareStatement("select pg_advisory_lock(?, ?)").use { statement ->
                    statement.setInt(1, TEST_LOCK_NAMESPACE)
                    statement.setInt(2, TEST_LOCK_RESOURCE)
                    statement.execute()
                }
                var triggerReleased = false
                try {
                    val ticketFuture = executor.submit<TicketCommandResult> {
                        ticketCommands.create(
                            CreateAgentTicketCommand(
                                requesterId = customerId,
                                subject = "동시 배정 문의",
                                firstComment = AgentCommentDraft(CommentVisibility.INTERNAL, "동시성 검증"),
                                priority = TicketPriority.NORMAL,
                                groupId = groupId,
                                assigneeId = agent.id,
                                actor = StaffTicketCommandActor(agent.id, agent.displayName, false),
                                context = CommandContext(
                                    source = RequestSource.AGENT_UI,
                                    requestId = "assignment-race-ticket",
                                    correlationId = "assignment-race",
                                    commandId = "assignment-race-ticket",
                                ),
                            ),
                        )
                    }
                    assertThat(awaitAdvisoryWaiter(TEST_LOCK_NAMESPACE, TEST_LOCK_RESOURCE)).isTrue()

                    val adminStarted = CountDownLatch(1)
                    val disableFuture = executor.submit<Throwable?> {
                        adminStarted.countDown()
                        runCatching {
                            asAdmin(admin) {
                                administration.disableGroup(groupId, admin.actor())
                            }
                        }.exceptionOrNull()
                    }
                    assertThat(adminStarted.await(5, TimeUnit.SECONDS)).isTrue()
                    val waitedOnSharedLock = awaitAdvisoryWaiter(
                        ORGANIZATION_LOCK_NAMESPACE,
                        ORGANIZATION_LOCK_RESOURCE,
                    )

                    releaseTestLock(blocker)
                    triggerReleased = true

                    assertThat(ticketFuture.get(10, TimeUnit.SECONDS).ticketNumber).isGreaterThan(0)
                    assertThat(waitedOnSharedLock).isTrue()
                    assertThat(rootCause(checkNotNull(disableFuture.get(10, TimeUnit.SECONDS))))
                        .isInstanceOf(OrganizationConflictException::class.java)
                } finally {
                    if (!triggerReleased) releaseTestLock(blocker)
                    executor.shutdownNow()
                    executor.awaitTermination(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            removeBlockingTicketInsertTrigger()
        }

        assertThat(
            jdbcTemplate.queryForObject(
                "select status from support_groups where id = ?",
                String::class.java,
                groupId,
            ),
        ).isEqualTo("ACTIVE")
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from tickets where group_id = ? and assignee_id = ?",
                Long::class.java,
                groupId,
                agent.id,
            ),
        ).isEqualTo(1)
    }

    private fun insertStaff(email: String, role: String): AdminFixture {
        val id = UUID.randomUUID()
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
            if (role == "ADMIN") "관리자" else "상담사",
            role,
            BCryptPasswordEncoder(4).encode("Test password 42!"),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
            Timestamp.from(Instant.parse("2026-08-10T00:00:00Z")),
        )
        return AdminFixture(id, email, if (role == "ADMIN") "관리자" else "상담사")
    }

    private fun insertGroup(name: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, ?, 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            id,
            name,
        )
        return id
    }

    private fun insertMembership(groupId: UUID, staffId: UUID, status: String) {
        jdbcTemplate.update(
            """
            insert into group_memberships (id, group_id, staff_id, status, created_at, updated_at, version)
            values (?, ?, ?, ?, now(), now(), 0)
            """.trimIndent(),
            UUID.randomUUID(),
            groupId,
            staffId,
            status,
        )
    }

    private fun insertCustomer(email: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, '동시성 고객', ?, ?, now(), now())
            """.trimIndent(),
            id,
            email.lowercase(),
            email,
        )
        return id
    }

    private fun installBlockingTicketInsertTrigger() {
        jdbcTemplate.execute(
            """
            create or replace function block_ticket_assignment_test() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                perform pg_advisory_xact_lock($TEST_LOCK_NAMESPACE, $TEST_LOCK_RESOURCE);
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger block_ticket_assignment_test
            before insert on tickets
            for each row execute function block_ticket_assignment_test()
            """.trimIndent(),
        )
    }

    private fun removeBlockingTicketInsertTrigger() {
        jdbcTemplate.execute("drop trigger if exists block_ticket_assignment_test on tickets")
        jdbcTemplate.execute("drop function if exists block_ticket_assignment_test()")
    }

    private fun awaitAdvisoryWaiter(namespace: Int, resource: Int): Boolean {
        repeat(200) {
            val waiting = jdbcTemplate.queryForObject(
                """
                select count(*)
                from pg_locks
                where locktype = 'advisory'
                  and classid = ?
                  and objid = ?
                  and not granted
                """.trimIndent(),
                Long::class.java,
                namespace,
                resource,
            ) ?: 0
            if (waiting > 0) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun releaseTestLock(blocker: java.sql.Connection) {
        blocker.prepareStatement("select pg_advisory_unlock(?, ?)").use { statement ->
            statement.setInt(1, TEST_LOCK_NAMESPACE)
            statement.setInt(2, TEST_LOCK_RESOURCE)
            statement.execute()
        }
    }

    private fun asAdmin(admin: AdminFixture, command: () -> Unit) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken.authenticated(
            StaffPrincipal(admin.id, admin.email, admin.displayName, StaffRole.ADMIN),
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
        try {
            command()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun concurrently(vararg commands: () -> Unit): List<Throwable?> {
        val ready = CountDownLatch(commands.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(commands.size)
        try {
            val futures = commands.map { command ->
                executor.submit<Throwable?> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "Concurrent command did not start" }
                    runCatching(command).exceptionOrNull()
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun withUpdateDelay(table: String, command: () -> Unit) {
        require(table in setOf("staff_accounts", "support_groups", "group_memberships"))
        jdbcTemplate.execute(
            """
            create or replace function delay_organization_concurrency_test() returns trigger language plpgsql as ${'$'}${'$'}
            begin
                perform pg_sleep(0.25);
                return new;
            end;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            """
            create trigger delay_organization_concurrency_test
            before update on $table
            for each row execute function delay_organization_concurrency_test()
            """.trimIndent(),
        )
        try {
            command()
        } finally {
            jdbcTemplate.execute("drop trigger if exists delay_organization_concurrency_test on $table")
            jdbcTemplate.execute("drop function if exists delay_organization_concurrency_test()")
        }
    }

    private fun rootCause(failure: Throwable): Throwable =
        generateSequence(failure) { it.cause }.last()

    private data class AdminFixture(val id: UUID, val email: String, val displayName: String) {
        fun actor() = AdminActorContext(
            staffId = id,
            displayName = displayName,
            source = RequestSource.ADMIN_UI,
            requestId = "request-$id",
            correlationId = "correlation-$id",
        )
    }

    companion object {
        private const val ORGANIZATION_LOCK_NAMESPACE = 1_146_309_957
        private const val ORGANIZATION_LOCK_RESOURCE = 1_330_797_127
        private const val TEST_LOCK_NAMESPACE = 1_146_309_958
        private const val TEST_LOCK_RESOURCE = 1_330_797_128

        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
