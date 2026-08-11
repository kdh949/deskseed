package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.AdminActorContext
import dev.deskseed.organization.OrganizationConflictException
import dev.deskseed.organization.OrganizationAdministration
import dev.deskseed.organization.StaffRole
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

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@Testcontainers
class OrganizationConcurrencyIntegrationTest {
    @Autowired
    private lateinit var administration: OrganizationAdministration

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearState() {
        jdbcTemplate.execute("truncate table admin_security_audit_events")
        jdbcTemplate.update("delete from request_access_tokens")
        jdbcTemplate.update("delete from ticket_comments")
        jdbcTemplate.update("delete from tickets")
        jdbcTemplate.update("delete from customers")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_login_throttles")
        jdbcTemplate.update("delete from staff_accounts")
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
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
