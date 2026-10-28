package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.StaffTicketSearchCursor
import dev.deskseed.ticketing.StaffTicketSearchFilter
import dev.deskseed.ticketing.StaffTicketSearchPartition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class StaffTicketSearchSqlPlanTest {
    @Test
    fun `search binds query as parameters without interpolating it into SQL`() {
        val rawQuery = "sensitive@example.test"
        val plan = StaffTicketSearchSqlPlanFactory().build(
            query = rawQuery,
            actorId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            filters = StaffTicketSearchFilter(null, null, null, null, null),
            sort = STAFF_SEARCH_SCORE_SORT,
            snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
            cursor = null,
            limit = 26,
            now = Instant.parse("2026-09-19T00:00:00Z"),
        )

        assertThat(plan.countSql).doesNotContain(rawQuery)
        assertThat(plan.pageSql).doesNotContain(rawQuery)
        assertThat(plan.parameters.getValue("queryText")).isEqualTo(rawQuery)
    }

    @Test
    fun `page limits minimal candidates before joining detail projections`() {
        val plan = buildPlan()

        val limit = requireNotNull(Regex("""\blimit\b""", RegexOption.IGNORE_CASE).find(plan.pageSql))
        val detailJoins = Regex(
            """\bjoin\s+(customers|support_groups|staff_accounts|analytics_first_reply_facts)\b""",
            RegexOption.IGNORE_CASE,
        ).findAll(plan.pageSql).toList()

        assertThat(detailJoins).isNotEmpty()
        assertThat(detailJoins.all { it.range.first > limit.range.first }).isTrue()
    }

    @Test
    fun `terminal partition uses only the immutable closed-ticket projection`() {
        val plan = StaffTicketSearchSqlPlanFactory().build(
            query = "closed fixture",
            actorId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            filters = StaffTicketSearchFilter(status = dev.deskseed.ticketing.TicketStatus.CLOSED),
            sort = STAFF_SEARCH_SCORE_SORT,
            snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
            cursor = null,
            limit = 26,
            now = Instant.parse("2026-09-19T00:00:00Z"),
            partition = StaffTicketSearchPartition.TERMINAL,
        )

        assertThat(plan.countSql).contains("join terminal_ticket_search_documents search_document")
            .doesNotContain("join ticket_search_documents search_document")
        assertThat(plan.pageSql).contains("join terminal_ticket_search_documents search_document")
            .doesNotContain("join active_ticket_search_documents search_document")
    }

    @Test
    fun `numeric query uses only exact ticket number predicate without substring scoring`() {
        val plan = StaffTicketSearchSqlPlanFactory().build(
            query = "8802",
            actorId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            filters = StaffTicketSearchFilter(null, null, null, null, null),
            sort = STAFF_SEARCH_SCORE_SORT,
            snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
            cursor = null,
            limit = 26,
            now = Instant.parse("2026-09-19T00:00:00Z"),
        )

        assertThat(plan.countSql)
            .contains("search_document.ticket_number = :ticketNumberQuery")
            .doesNotContain("staff_document like", "queryPattern", "strpos")
        assertThat(plan.pageSql)
            .contains("1000 as search_score")
            .doesNotContain("staff_document like", "queryPattern", "strpos")
        assertThat(plan.parameters.parameterNames)
            .contains("ticketNumberQuery")
            .doesNotContain("queryText", "queryPattern")
    }

    @Test
    fun `updated sort skips relevance score calculation while preserving literal candidate matching`() {
        val plan = buildPlan(sort = STAFF_SEARCH_UPDATED_SORT, cursor = null)

        assertThat(plan.countSql).contains("search_document.staff_document like lower(:queryPattern)")
        assertThat(plan.pageSql)
            .contains("0 as search_score", "order by updated_at desc, ticket_number desc")
            .doesNotContain("strpos(")
    }

    private fun buildPlan(sort: String = STAFF_SEARCH_SCORE_SORT, cursor: StaffTicketSearchCursor? = null) =
        StaffTicketSearchSqlPlanFactory().build(
            query = "synthetic query",
            actorId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            filters = StaffTicketSearchFilter(null, null, null, null, null),
            sort = sort,
            snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
            cursor = cursor,
            limit = 26,
            now = Instant.parse("2026-09-19T00:00:00Z"),
        )
}
