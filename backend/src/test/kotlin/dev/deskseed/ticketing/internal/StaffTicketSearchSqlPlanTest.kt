package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.StaffTicketSearchFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@dev.deskseed.testsupport.category.FastTest
class StaffTicketSearchSqlPlanTest {
    @Test
    fun `count and page share the canonical predicate and keep query in parameters`() {
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
        assertThat(plan.pageSql).startsWith("with ranked as").contains("join ticket_search_documents search_document")
        assertThat(plan.countSql).doesNotContain(rawQuery)
        assertThat(plan.pageSql).doesNotContain(rawQuery)
        assertThat(plan.parameters.getValue("queryText")).isEqualTo(rawQuery)
        assertThat(plan.parameters.parameterNames).contains(
            "actorId", "ticketNumberQuery", "queryText", "queryPattern", "limit", "now", "riskAt", "snapshotAt",
        )
    }
}
