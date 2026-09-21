package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.StaffTicketSearchCursor
import dev.deskseed.ticketing.StaffTicketSearchFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class StaffTicketSearchSqlPlanTest {
    @Test
    fun `diagnostic count and runtime page share the canonical predicate while page omits exact count`() {
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

        assertThat(plan.countSql).startsWith("select count(*)").contains("join ticket_search_documents search_document")
        assertThat(plan.pageSql).startsWith("with ranked as not materialized")
            .contains("join ticket_search_documents search_document")
            .doesNotContain("candidate_stats", "select count(*) as result_count", "result_count")
        assertThat(plan.countSql).doesNotContain(rawQuery)
        assertThat(plan.pageSql).doesNotContain(rawQuery)
        assertThat(plan.parameters.getValue("queryText")).isEqualTo(rawQuery)
        assertThat(plan.parameters.parameterNames).contains(
            "actorId", "ticketNumberQuery", "queryText", "queryPattern", "limit", "now", "riskAt", "snapshotAt",
        )
    }

    @Test
    fun `page limits minimal candidates before joining detail projections`() {
        val plan = buildPlan(sort = STAFF_SEARCH_SCORE_SORT, cursor = null)

        assertThat(plan.pageSql).contains(
            "select t.ticket_number,",
            "select ticket_number, search_score",
            "t.id as selected_ticket_id",
            "join tickets t on t.ticket_number = selected.ticket_number",
        )
        assertThat(plan.pageSql.substringBefore("limit :limit"))
            .doesNotContain("t.id as ticket_id", "select ticket_id", "t.updated_at,")
        assertThat(plan.pageSql.indexOf("limit :limit"))
            .isLessThan(plan.pageSql.indexOf("left join customers c"))
        assertThat(plan.pageSql.substringBefore("limit :limit"))
            .doesNotContain(
                "left join customers c",
                "left join support_groups g",
                "left join staff_accounts s",
                "left join analytics_first_reply_facts fact",
            )
        assertThat(plan.pageSql.substringAfter("limit :limit"))
            .contains("left join customers c", "left join support_groups g", "left join staff_accounts s")
    }

    @Test
    fun `runtime page applies cursor without calculating exact count`() {
        val plan = buildPlan(
            sort = STAFF_SEARCH_SCORE_SORT,
            cursor = StaffTicketSearchCursor(
                snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
                lastScore = 250,
                lastTicketNumber = 1042,
                returnedBefore = 25,
            ),
        )

        assertThat(plan.pageSql).contains("where (search_score, ticket_number)")
            .doesNotContain("select count(*) from ranked", "candidate_stats", "result_count")
    }

    @Test
    fun `both cursor predicates are applied before detail projection and keep stable final order`() {
        val scorePlan = buildPlan(
            sort = STAFF_SEARCH_SCORE_SORT,
            cursor = StaffTicketSearchCursor(
                snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
                lastScore = 250,
                lastTicketNumber = 1042,
                returnedBefore = 25,
            ),
        )
        val updatedPlan = buildPlan(
            sort = STAFF_SEARCH_UPDATED_SORT,
            cursor = StaffTicketSearchCursor(
                snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
                lastUpdatedAt = Instant.parse("2026-09-18T12:00:00Z"),
                lastTicketNumber = 1042,
                returnedBefore = 25,
            ),
        )

        assertThat(scorePlan.pageSql.substringBefore("limit :limit"))
            .contains("where (search_score, ticket_number) < (:cursorScore, :cursorTicketNumber)")
        assertThat(scorePlan.pageSql).endsWith("order by selected.search_score desc, selected.ticket_number desc")
        assertThat(updatedPlan.pageSql.substringBefore("limit :limit"))
            .contains(
                "select t.ticket_number, t.updated_at,",
                "select ticket_number, updated_at, search_score",
                "where (updated_at, ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)",
            )
        assertThat(updatedPlan.pageSql).endsWith("order by selected.updated_at desc, selected.ticket_number desc")
    }

    private fun buildPlan(sort: String, cursor: StaffTicketSearchCursor?) =
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
