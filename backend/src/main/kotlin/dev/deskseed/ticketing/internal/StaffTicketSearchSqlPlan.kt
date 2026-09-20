package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.StaffTicketSearchCursor
import dev.deskseed.ticketing.StaffTicketSearchFilter
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal const val STAFF_SEARCH_UPDATED_SORT = "updatedAt:desc,ticketNumber:desc"
internal const val STAFF_SEARCH_SCORE_SORT = "score:desc,ticketNumber:desc"
internal val STAFF_SEARCH_SORTS = setOf(STAFF_SEARCH_UPDATED_SORT, STAFF_SEARCH_SCORE_SORT)

internal data class StaffTicketSearchSqlPlan(
    val countSql: String,
    val pageSql: String,
    val parameters: MapSqlParameterSource,
)

/** Canonical SQL and parameter schema shared by runtime search and the load-only plan capture tool. */
@Component
internal class StaffTicketSearchSqlPlanFactory {
    fun build(
        query: String,
        actorId: UUID,
        filters: StaffTicketSearchFilter,
        sort: String,
        snapshotAt: Instant,
        cursor: StaffTicketSearchCursor?,
        limit: Int,
        now: Instant,
    ): StaffTicketSearchSqlPlan {
        require(sort in STAFF_SEARCH_SORTS) { "Unsupported ticket search sort" }
        val riskAt = now.plusSeconds(30 * 60)
        val trimmedQuery = query.trim()
        val queryCharacterCount = trimmedQuery.codePointCount(0, trimmedQuery.length)
        val parameters = MapSqlParameterSource()
            .addValue("actorId", actorId)
            .addValue("ticketNumberQuery", trimmedQuery.toLongOrNull())
            .addValue("queryText", trimmedQuery)
            .addValue("queryPattern", likeLiteralPattern(trimmedQuery))
            .addValue("limit", limit)
            .addValue("now", Timestamp.from(now))
            .addValue("riskAt", Timestamp.from(riskAt))
            .addValue("snapshotAt", Timestamp.from(snapshotAt))
        val documentPredicate = if (queryCharacterCount <= 2) {
            """
            (
                staff_ticket_search_characters(search_document.staff_document)
                    @> staff_ticket_search_characters(cast(:queryText as text))
                and search_document.staff_document like lower(:queryPattern) escape '\'
            )
            """.trimIndent()
        } else {
            "search_document.staff_document like lower(:queryPattern) escape '\\'"
        }
        val conditions = mutableListOf(
            """
            exists (
                select 1 from staff_accounts authorized_actor
                where authorized_actor.id = :actorId and authorized_actor.status = 'ACTIVE'
            )
            """.trimIndent().trim(),
            "t.updated_at <= :snapshotAt",
            """
            (
                (cast(:ticketNumberQuery as bigint) is not null
                    and search_document.ticket_number = cast(:ticketNumberQuery as bigint))
                or $documentPredicate
            )
            """.trimIndent(),
        )
        conditions += compileFilters(filters, parameters)
        val candidateFromClause = """
            from tickets t
            join ticket_search_documents search_document on search_document.ticket_id = t.id
            ${if (filters.slaState != null) "left join analytics_first_reply_facts fact on fact.ticket_id = t.id" else ""}
            where ${conditions.joinToString("\n  and ")}
        """.trimIndent()
        val rankedSortColumns = if (sort == STAFF_SEARCH_UPDATED_SORT) "t.updated_at," else ""
        val selectedColumns = if (sort == STAFF_SEARCH_UPDATED_SORT) {
            "ticket_number, updated_at, search_score"
        } else {
            "ticket_number, search_score"
        }
        val rankedCandidates = """
            select t.ticket_number, $rankedSortColumns
                   ${searchScoreExpression()} as search_score
            $candidateFromClause
        """.trimIndent()
        val cursorPredicate = when (sort) {
            STAFF_SEARCH_SCORE_SORT -> cursor?.let {
                parameters.addValue("cursorScore", checkNotNull(it.lastScore))
                parameters.addValue("cursorTicketNumber", it.lastTicketNumber)
                "where (search_score, ticket_number) < (:cursorScore, :cursorTicketNumber)"
            }.orEmpty()
            STAFF_SEARCH_UPDATED_SORT -> cursor?.let {
                parameters.addValue("cursorUpdatedAt", Timestamp.from(checkNotNull(it.lastUpdatedAt)))
                parameters.addValue("cursorTicketNumber", it.lastTicketNumber)
                "where (updated_at, ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)"
            }.orEmpty()
            else -> error("Validated above")
        }
        val orderBy = if (sort == STAFF_SEARCH_SCORE_SORT) {
            "search_score desc, ticket_number desc"
        } else {
            "updated_at desc, ticket_number desc"
        }
        val selectedOrderBy = if (sort == STAFF_SEARCH_SCORE_SORT) {
            "selected.search_score desc, selected.ticket_number desc"
        } else {
            "selected.updated_at desc, selected.ticket_number desc"
        }
        return StaffTicketSearchSqlPlan(
            countSql = "select count(*) $candidateFromClause",
            pageSql = """
                with ranked as materialized (
                    $rankedCandidates
                ),
                candidate_stats as materialized (
                    select count(*) as result_count from ranked
                ),
                selected as materialized (
                    select $selectedColumns
                    from ranked
                    $cursorPredicate
                    order by $orderBy
                    limit :limit
                )
                select t.id as selected_ticket_id,
                       candidate_stats.result_count,
                       ${ticketSummaryColumns()},
                       selected.search_score
                from candidate_stats
                left join selected on true
                left join tickets t on t.ticket_number = selected.ticket_number
                left join customers c on c.id = t.requester_id
                left join support_groups g on g.id = t.group_id
                left join staff_accounts s on s.id = t.assignee_id
                left join analytics_first_reply_facts fact on fact.ticket_id = t.id
                order by $selectedOrderBy
            """.trimIndent().trim(),
            parameters = parameters,
        )
    }

    private fun compileFilters(
        filters: StaffTicketSearchFilter,
        parameters: MapSqlParameterSource,
    ): List<String> = buildList {
        filters.status?.let {
            add("t.status = :searchStatus")
            parameters.addValue("searchStatus", it.name)
        }
        filters.priority?.let {
            add("t.priority = :searchPriority")
            parameters.addValue("searchPriority", it.name)
        }
        filters.groupId?.let {
            add("t.group_id = :searchGroupId")
            parameters.addValue("searchGroupId", it)
        }
        filters.assignee?.let { assignee ->
            when (assignee) {
                "me" -> add("t.assignee_id = :actorId")
                "unassigned" -> add("t.assignee_id is null")
                else -> {
                    add("t.assignee_id = :searchAssigneeId")
                    parameters.addValue("searchAssigneeId", UUID.fromString(assignee))
                }
            }
        }
        filters.slaState?.let {
            add("""
                (case
                    when fact.outcome = 'ACTIVE' and fact.due_at <= :now then 'BREACHED'
                    when fact.outcome = 'ACTIVE' and fact.due_at <= :riskAt then 'AT_RISK'
                    when fact.outcome is null and t.kind = 'CUSTOMER_REQUEST' then 'NO_POLICY'
                    else fact.outcome
                end) = :searchSlaState
            """.trimIndent())
            parameters.addValue("searchSlaState", it.name)
        }
    }

    private fun likeLiteralPattern(query: String): String = "%${
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }%"

    private fun ticketSummaryColumns(): String = """
        t.id, t.ticket_number, t.subject, t.status, t.priority,
        t.created_at, t.updated_at, t.version, t.kind,
        (select count(*)
         from ticket_relations relation
         join tickets child on child.id = relation.target_ticket_id
         where relation.source_ticket_id = t.id
           and relation.relation_type = 'PARENT_CHILD'
           and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
        c.id as customer_id, c.name as customer_name,
        g.id as group_id, g.name as group_name,
        s.id as assignee_id, s.display_name as assignee_name,
        fact.outcome as sla_outcome, fact.due_at as sla_due_at,
        fact.target_minutes as sla_target_minutes, fact.policy_version as sla_policy_version,
        fact.schedule_version as sla_schedule_version
    """.trimIndent()

    private fun searchScoreExpression(): String = """
        (
            case when cast(:ticketNumberQuery as bigint) is not null
                    and search_document.ticket_number = cast(:ticketNumberQuery as bigint) then 1000 else 0 end
            + case when search_document.subject_text = lower(:queryText) then 500
                   when strpos(search_document.subject_text, lower(:queryText)) > 0 then 250 else 0 end
            + case when search_document.requester_name_text = lower(:queryText) then 180
                   when strpos(search_document.requester_name_text, lower(:queryText)) > 0 then 90 else 0 end
            + case when search_document.requester_email_text = lower(:queryText) then 160
                   when strpos(search_document.requester_email_text, lower(:queryText)) > 0 then 80 else 0 end
            + case when search_document.group_name_text = lower(:queryText) then 80
                   when strpos(search_document.group_name_text, lower(:queryText)) > 0 then 40 else 0 end
            + case when search_document.assignee_name_text = lower(:queryText) then 80
                   when strpos(search_document.assignee_name_text, lower(:queryText)) > 0 then 40 else 0 end
            + case when strpos(search_document.public_comment_text, lower(:queryText)) > 0
                         or strpos(search_document.internal_comment_text, lower(:queryText)) > 0
                   then 20 else 0 end
        )
    """.trimIndent()
}
