package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.ticketing.StaffTicketReadScope
import dev.deskseed.ticketing.StaffTicketSearchFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@dev.deskseed.testsupport.integration.DeskseedSpringIntegrationTest
@Import(QueryCountTestConfiguration::class)
@dev.deskseed.testsupport.category.IntegrationTest
class StaffTicketQueryEvidenceIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var ticketStore: StaffTicketReadStore

    @Autowired
    private lateinit var queryCounter: JdbcQueryCounter

    private lateinit var staffId: UUID
    private lateinit var groupId: UUID
    private lateinit var customerId: UUID
    private lateinit var ticketId: UUID

    @BeforeEach
    fun seed() {
        jdbcTemplate.execute(
            "truncate table macro_preview_audit_details, search_audit_query_ciphertexts, " +
                "search_audit_customer_result_items, " +
                "search_audit_result_items, search_audit_details, access_audit_events",
        )
        jdbcTemplate.execute(
            "truncate table customer_registration_intent_consents, customer_registration_intents, " +
                "customer_consent_acceptances, customer_consent_policy_versions, customer_consent_policies cascade",
        )
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
        groupId = UUID.randomUUID()
        customerId = UUID.randomUUID()
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
    fun `list uses one SQL statement and detail uses four bounded statements regardless of comment count`() {
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
        // P1 adds one bulk attachment projection query; it must not grow with comment count.
        assertThat(queryCounter.count()).isEqualTo(4)
    }

    @Test
    fun `search uses a fixed count and result query regardless of comment count`() {
        queryCounter.reset()

        val result = ticketStore.search(
            query = "대화 99",
            scope = StaffTicketReadScope.ALL_TICKETS,
            actorId = staffId,
            filters = StaffTicketSearchFilter(),
            limit = 25,
        )

        assertThat(result.resultCount).isEqualTo(1)
        assertThat(result.items.map { it.ticketNumber }).containsExactly(6001)
        assertThat(queryCounter.count()).isEqualTo(2)
    }

    @Test
    @Transactional
    fun `staff search projection separates visibility stays transactionally fresh and uses its trigram index`() {
        val internalCommentId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            insert into ticket_comments
                (id, ticket_id, author_type, author_id, visibility, body, created_at)
            values (?, ?, 'AGENT', ?, 'INTERNAL', '내부 전용 초기 단어', now())
            """.trimIndent(),
            internalCommentId,
            ticketId,
            staffId,
        )

        val initial = jdbcTemplate.queryForMap(
            """
            select document_version, public_comment_text, internal_comment_text, staff_document
            from ticket_search_documents where ticket_id = ?
            """.trimIndent(),
            ticketId,
        )
        assertThat(initial["document_version"]).isEqualTo(1)
        assertThat(initial["public_comment_text"].toString()).contains("대화 99")
            .doesNotContain("내부 전용 초기 단어")
        assertThat(initial["internal_comment_text"].toString()).contains("내부 전용 초기 단어")
        assertThat(initial["staff_document"].toString()).contains("대화 99", "내부 전용 초기 단어")

        jdbcTemplate.update(
            "update ticket_comments set body = '내부 전용 변경 단어' where id = ?",
            internalCommentId,
        )
        jdbcTemplate.update("update tickets set subject = '변경된 검색 제목' where id = ?", ticketId)
        jdbcTemplate.update(
            "update customers set name = '변경된 요청자', email_normalized = 'changed@example.com' where id = ?",
            customerId,
        )
        jdbcTemplate.update("update support_groups set name = '변경된 검색 그룹' where id = ?", groupId)
        jdbcTemplate.update("update staff_accounts set display_name = '변경된 담당자' where id = ?", staffId)

        val refreshed = jdbcTemplate.queryForMap(
            """
            select subject_text, requester_name_text, requester_email_text,
                   group_name_text, assignee_name_text, internal_comment_text, staff_document
            from ticket_search_documents where ticket_id = ?
            """.trimIndent(),
            ticketId,
        )
        assertThat(refreshed["subject_text"]).isEqualTo("변경된 검색 제목")
        assertThat(refreshed["requester_name_text"]).isEqualTo("변경된 요청자")
        assertThat(refreshed["requester_email_text"]).isEqualTo("changed@example.com")
        assertThat(refreshed["group_name_text"]).isEqualTo("변경된 검색 그룹")
        assertThat(refreshed["assignee_name_text"]).isEqualTo("변경된 담당자")
        assertThat(refreshed["internal_comment_text"].toString())
            .contains("내부 전용 변경 단어")
            .doesNotContain("내부 전용 초기 단어")
        assertThat(refreshed["staff_document"].toString())
            .contains("변경된 검색 제목", "변경된 요청자", "변경된 검색 그룹", "변경된 담당자")

        jdbcTemplate.execute("set local enable_seqscan = off")
        val plan = jdbcTemplate.queryForList(
            """
            explain (costs off)
            select ticket_id from ticket_search_documents
            where staff_document like '%대화 99%' escape '\'
            """.trimIndent(),
            String::class.java,
        ).joinToString("\n")
        assertThat(plan).contains("ticket_search_documents_staff_trgm_idx")

        jdbcTemplate.update("delete from ticket_comments where id = ?", internalCommentId)
        assertThat(
            jdbcTemplate.queryForObject(
                "select internal_comment_text from ticket_search_documents where ticket_id = ?",
                String::class.java,
                ticketId,
            ),
        ).doesNotContain("내부 전용 변경 단어")

        jdbcTemplate.update(
            "update ticket_search_documents set subject_text = '의도적 drift' where ticket_id = ?",
            ticketId,
        )
        assertThat(
            jdbcTemplate.queryForObject("select rebuild_ticket_search_documents()", Long::class.java),
        ).isEqualTo(40L)
        assertThat(
            jdbcTemplate.queryForObject(
                "select subject_text from ticket_search_documents where ticket_id = ?",
                String::class.java,
                ticketId,
            ),
        ).isEqualTo("변경된 검색 제목")
    }

    @Test
    fun `unsupported search scope fails before issuing SQL`() {
        queryCounter.reset()

        assertThatThrownBy {
            ticketStore.search(
                query = "쿼리",
                scope = StaffTicketReadScope.OWN_GROUPS,
                actorId = staffId,
                filters = StaffTicketSearchFilter(),
                limit = 25,
            )
        }.isInstanceOf(InvalidDataAccessApiUsageException::class.java)
            .hasRootCauseInstanceOf(IllegalArgumentException::class.java)
        assertThat(queryCounter.count()).isZero()
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
