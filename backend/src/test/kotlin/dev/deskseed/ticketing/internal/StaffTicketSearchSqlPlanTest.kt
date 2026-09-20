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
    fun `count and combined page share the canonical predicate and keep query in parameters`() {
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
        assertThat(plan.pageSql).startsWith("with ranked as materialized")
            .contains("join ticket_search_documents search_document")
            .contains("candidate_stats as materialized", "select count(*) as result_count from ranked")
            .contains("left join selected on true")
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
            "select t.id as ticket_id, t.ticket_number, t.updated_at",
            "selected.ticket_id as selected_ticket_id",
            "candidate_stats.result_count",
            "join tickets t on t.id = selected.ticket_id",
        )
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
    fun `combined page counts all snapshot candidates before applying cursor`() {
        val plan = buildPlan(
            sort = STAFF_SEARCH_SCORE_SORT,
            cursor = StaffTicketSearchCursor(
                snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
                lastScore = 250,
                lastTicketNumber = 1042,
            ),
        )

        assertThat(plan.pageSql.indexOf("select count(*) as result_count from ranked"))
            .isLessThan(plan.pageSql.indexOf("where (search_score, ticket_number)"))
        assertThat(plan.pageSql).contains("from candidate_stats", "left join selected on true")
    }

    @Test
    fun `both cursor predicates are applied before detail projection and keep stable final order`() {
        val scorePlan = buildPlan(
            sort = STAFF_SEARCH_SCORE_SORT,
            cursor = StaffTicketSearchCursor(
                snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
                lastScore = 250,
                lastTicketNumber = 1042,
            ),
        )
        val updatedPlan = buildPlan(
            sort = STAFF_SEARCH_UPDATED_SORT,
            cursor = StaffTicketSearchCursor(
                snapshotAt = Instant.parse("2026-09-19T00:00:00Z"),
                lastUpdatedAt = Instant.parse("2026-09-18T12:00:00Z"),
                lastTicketNumber = 1042,
            ),
        )

        assertThat(scorePlan.pageSql.substringBefore("limit :limit"))
            .contains("where (search_score, ticket_number) < (:cursorScore, :cursorTicketNumber)")
        assertThat(scorePlan.pageSql).endsWith("order by selected.search_score desc, selected.ticket_number desc")
        assertThat(updatedPlan.pageSql.substringBefore("limit :limit"))
            .contains("where (updated_at, ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)")
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
