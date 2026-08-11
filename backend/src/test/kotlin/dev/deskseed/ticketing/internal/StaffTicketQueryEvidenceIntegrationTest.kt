package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketReadStore
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@SpringBootTest(properties = ["deskseed.staff-auth.bootstrap.enabled=false"])
@Testcontainers
@Import(QueryCountTestConfiguration::class)
class StaffTicketQueryEvidenceIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var ticketStore: StaffTicketReadStore

    @Autowired
    private lateinit var queryCounter: JdbcQueryCounter

    private lateinit var staffId: UUID
    private lateinit var ticketId: UUID

    @BeforeEach
    fun seed() {
        jdbcTemplate.execute("truncate table access_audit_events")
        jdbcTemplate.update("delete from request_access_tokens")
        jdbcTemplate.update("delete from ticket_audit_events")
        jdbcTemplate.update("delete from ticket_audits")
        jdbcTemplate.update("delete from ticket_comments")
        jdbcTemplate.update("delete from tickets")
        jdbcTemplate.update("delete from customers")
        jdbcTemplate.update("delete from group_memberships")
        jdbcTemplate.update("delete from support_groups")
        jdbcTemplate.update("delete from staff_accounts")

        staffId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val customerId = UUID.randomUUID()
        ticketId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into staff_accounts
                (id, email_normalized, email_display, display_name, role, status,
                 password_hash, created_at, updated_at, version)
            values (?, 'query@example.com', 'query@example.com', '쿼리 상담사', 'AGENT', 'ACTIVE',
                    'not-used-in-query-test', now(), now(), 0)
            """.trimIndent(),
            staffId,
        )
        jdbcTemplate.update(
            """
            insert into support_groups (id, name, status, created_at, updated_at, version)
            values (?, '쿼리 그룹', 'ACTIVE', now(), now(), 0)
            """.trimIndent(),
            groupId,
        )
        jdbcTemplate.update(
            """
            insert into customers (id, name, email_normalized, email_display, created_at, updated_at)
            values (?, '쿼리 고객', 'query-customer@example.com', 'query-customer@example.com', now(), now())
            """.trimIndent(),
            customerId,
        )
        repeat(40) { index ->
            val currentTicketId = if (index == 0) ticketId else UUID.randomUUID()
            jdbcTemplate.update(
                """
                insert into tickets
                    (id, ticket_number, requester_id, kind, subject, status, priority,
                     group_id, assignee_id, channel, version, created_at, updated_at)
                values (?, ?, ?, 'CUSTOMER_REQUEST', ?, 'OPEN', 'NORMAL', ?, ?, 'WEB', 0,
                        now() - interval '1 day', now() - (? * interval '1 second'))
                """.trimIndent(),
                currentTicketId,
                6001L + index,
                customerId,
                "쿼리 티켓 $index",
                groupId,
                staffId,
                index,
            )
        }
        repeat(100) { index ->
            jdbcTemplate.update(
                """
                insert into ticket_comments
                    (id, ticket_id, author_type, author_id, visibility, body, created_at)
                values (?, ?, 'CUSTOMER', ?, 'PUBLIC', ?, now() + (? * interval '1 millisecond'))
                """.trimIndent(),
                UUID.randomUUID(),
                ticketId,
                customerId,
                "대화 $index",
                index,
            )
        }
    }

    @Test
    fun `list uses one SQL statement and detail uses three regardless of comment count`() {
        queryCounter.reset()
        val page = ticketStore.list(
            view = DefaultStaffView.MY_OPEN,
            actorId = staffId,
            filters = StaffTicketListFilter(),
            cursor = null,
            limit = 21,
            recentlySolvedAfter = Instant.parse("2026-07-01T00:00:00Z"),
        )
        assertThat(page).hasSize(21)
        assertThat(queryCounter.count()).isEqualTo(1)

        queryCounter.reset()
        val detail = ticketStore.findDetail(6001)
        assertThat(detail?.comments).hasSize(100)
        assertThat(queryCounter.count()).isEqualTo(3)
    }

    @Test
    @Transactional
    fun `my open cursor query can use the assignee status cursor index`() {
        jdbcTemplate.execute("set local enable_seqscan = off")
        val plan = jdbcTemplate.queryForList(
            """
            explain (costs off)
            select t.id
            from tickets t
            join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
            where t.status = 'OPEN' and t.assignee_id = '$staffId'
            order by t.updated_at desc, t.ticket_number desc
            limit 20
            """.trimIndent(),
            String::class.java,
        ).joinToString("\n")

        assertThat(plan).contains("tickets_assignee_status_cursor_idx")
    }

    @Test
    fun `runtime application path cannot update or delete canonical access audit rows`() {
        val eventId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, request_id, correlation_id, outcome, http_status
            ) values (?, now(), 'STAFF', ?, '쿼리 상담사', 'AGENT_UI', 'TICKET_VIEWED',
                      'TICKET', ?, 6001, ?, 'append-only-request', 'append-only-correlation',
                      'SUCCEEDED', 200)
            """.trimIndent(),
            eventId,
            staffId,
            ticketId,
            UUID.randomUUID(),
        )

        assertThatThrownBy {
            jdbcTemplate.update("update access_audit_events set http_status = 204 where id = ?", eventId)
        }.hasMessageContaining("Access audit history is append-only")
        assertThatThrownBy {
            jdbcTemplate.update("delete from access_audit_events where id = ?", eventId)
        }.hasMessageContaining("Access audit history is append-only")
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}

internal class JdbcQueryCounter {
    private val statements = AtomicInteger()

    fun increment() = statements.incrementAndGet()

    fun reset() = statements.set(0)

    fun count(): Int = statements.get()
}

@TestConfiguration(proxyBeanMethods = false)
internal class QueryCountTestConfiguration {
    @Bean
    fun jdbcQueryCounter() = JdbcQueryCounter()

    @Bean
    fun countingDataSourcePostProcessor(counter: JdbcQueryCounter) = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
            if (bean is DataSource && bean !is CountingDataSource) CountingDataSource(bean, counter) else bean
    }
}

internal class CountingDataSource(
    targetDataSource: DataSource,
    private val counter: JdbcQueryCounter,
) : DelegatingDataSource(targetDataSource) {
    override fun getConnection(): Connection = counting(super.getConnection())

    override fun getConnection(username: String, password: String): Connection =
        counting(super.getConnection(username, password))

    private fun counting(connection: Connection): Connection = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, arguments ->
        if (method.name == "prepareStatement" || method.name == "prepareCall") counter.increment()
        try {
            method.invoke(connection, *(arguments ?: emptyArray()))
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    } as Connection
}
