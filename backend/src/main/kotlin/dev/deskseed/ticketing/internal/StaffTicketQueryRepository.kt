package dev.deskseed.ticketing.internal

import dev.deskseed.attachments.AttachmentVisibility
import dev.deskseed.attachments.TicketAttachmentReadProjection
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.DefaultStaffView
import dev.deskseed.ticketing.StaffActorSummary
import dev.deskseed.ticketing.StaffCommentView
import dev.deskseed.ticketing.StaffGroupReference
import dev.deskseed.ticketing.StaffReference
import dev.deskseed.ticketing.StaffTicketCursor
import dev.deskseed.ticketing.StaffTicketCustomer
import dev.deskseed.ticketing.StaffTicketDetail
import dev.deskseed.ticketing.StaffTicketHistoryItem
import dev.deskseed.ticketing.StaffTicketListFilter
import dev.deskseed.ticketing.StaffTicketReadStore
import dev.deskseed.ticketing.StaffTicketReadScope
import dev.deskseed.ticketing.StaffTicketSearchCursor
import dev.deskseed.ticketing.StaffTicketSearchFilter
import dev.deskseed.ticketing.StaffTicketSearchHit
import dev.deskseed.ticketing.StaffTicketSearchResult
import dev.deskseed.ticketing.StaffTicketSummary
import dev.deskseed.ticketing.StaffSlaBadge
import dev.deskseed.ticketing.StaffSlaDisplayState
import dev.deskseed.ticketing.SavedTicketView
import dev.deskseed.ticketing.SavedViewCountBatch
import dev.deskseed.ticketing.SavedViewCondition
import dev.deskseed.ticketing.SavedViewConditionField
import dev.deskseed.ticketing.SavedViewConditionOperator
import dev.deskseed.ticketing.SavedViewConditions
import dev.deskseed.ticketing.SavedViewDefinitionRules
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.sql.Timestamp
import java.sql.ResultSet
import java.util.UUID

@Repository
internal class StaffTicketQueryRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
    private val attachmentReadProjection: TicketAttachmentReadProjection,
    private val clock: Clock,
) : StaffTicketReadStore {
    override fun list(
        view: DefaultStaffView,
        actorId: UUID,
        filters: StaffTicketListFilter,
        cursor: StaffTicketCursor?,
        limit: Int,
        recentlySolvedAfter: Instant,
    ): List<StaffTicketSummary> {
        val now = clock.instant()
        val riskAt = now.plusSeconds(30 * 60)
        val conditions = mutableListOf<String>()
        val parameters = MapSqlParameterSource()
            .addValue("actorId", actorId)
            .addValue("recentlySolvedAfter", Timestamp.from(recentlySolvedAfter))
            .addValue("limit", limit)
            .addValue("now", Timestamp.from(now))
            .addValue("riskAt", Timestamp.from(riskAt))

        conditions += when (view) {
            DefaultStaffView.MY_OPEN -> "t.status = 'OPEN' and t.assignee_id = :actorId"
            DefaultStaffView.UNASSIGNED_MY_GROUPS -> """
                t.assignee_id is null
                and t.status not in ('SOLVED', 'CLOSED')
                and t.group_id in (
                    select gm.group_id from group_memberships gm
                    join support_groups mg on mg.id = gm.group_id and mg.status = 'ACTIVE'
                    where gm.staff_id = :actorId and gm.status = 'ACTIVE'
                )
            """.trimIndent()
            DefaultStaffView.PENDING -> "t.status = 'PENDING'"
            DefaultStaffView.RECENTLY_SOLVED -> """
                t.status = 'SOLVED'
                and t.assignee_id = :actorId
                and t.updated_at >= :recentlySolvedAfter
            """.trimIndent()
            DefaultStaffView.MY_CHILD_TASKS -> """
                t.kind = 'INTERNAL_CHILD'
                and t.assignee_id = :actorId
                and t.status not in ('SOLVED', 'CLOSED')
            """.trimIndent()
        }

        filters.status?.let {
            conditions += "t.status = :status"
            parameters.addValue("status", it.name)
        }
        filters.priority?.let {
            conditions += "t.priority = :priority"
            parameters.addValue("priority", it.name)
        }
        filters.groupId?.let {
            conditions += "t.group_id = :groupId"
            parameters.addValue("groupId", it)
        }
        filters.assignee?.let { assignee ->
            when (assignee) {
                "me" -> conditions += "t.assignee_id = :actorId"
                "unassigned" -> conditions += "t.assignee_id is null"
                else -> {
                    conditions += "t.assignee_id = :filterAssigneeId"
                    parameters.addValue("filterAssigneeId", UUID.fromString(assignee))
                }
            }
        }
        filters.slaState?.let {
            conditions += """
                (case
                    when fact.outcome = 'ACTIVE' and fact.due_at <= :now then 'BREACHED'
                    when fact.outcome = 'ACTIVE' and fact.due_at <= :riskAt then 'AT_RISK'
                    when fact.outcome is null and t.kind = 'CUSTOMER_REQUEST' then 'NO_POLICY'
                    else fact.outcome
                end) = :slaState
            """.trimIndent()
            parameters.addValue("slaState", it.name)
        }
        cursor?.let {
            conditions += "(t.updated_at, t.ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)"
            parameters.addValue("cursorUpdatedAt", Timestamp.from(it.updatedAt))
            parameters.addValue("cursorTicketNumber", it.ticketNumber)
        }

        return jdbcTemplate.query(
            """
            select t.id, t.ticket_number, t.subject, t.status, t.priority,
                   t.created_at, t.updated_at, t.version, t.kind,
                   (select count(*)
                    from ticket_relations relation
                    join tickets child on child.id = relation.target_ticket_id
                    where relation.source_ticket_id = t.id
                      and relation.relation_type = 'PARENT_CHILD'
                      and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
                   c.id as customer_id, c.name as customer_name,
                   g.id as group_id, g.name as group_name,
                   s.id as assignee_id, s.display_name as assignee_name
                   , fact.outcome as sla_outcome, fact.due_at as sla_due_at,
                   fact.target_minutes as sla_target_minutes, fact.policy_version as sla_policy_version,
                   fact.schedule_version as sla_schedule_version
            from tickets t
            left join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
            left join analytics_first_reply_facts fact on fact.ticket_id = t.id
            where ${conditions.joinToString("\n  and ")}
            order by t.updated_at desc, t.ticket_number desc
            limit :limit
            """.trimIndent(),
            parameters,
        ) { result, _ ->
            StaffTicketSummary(
                id = result.getObject("id", UUID::class.java),
                ticketNumber = result.getLong("ticket_number"),
                subject = result.getString("subject"),
                status = TicketStatus.valueOf(result.getString("status")),
                priority = TicketPriority.valueOf(result.getString("priority")),
                requester = requesterSummary(result),
                group = result.getObject("group_id", UUID::class.java)?.let {
                    StaffGroupReference(it, result.getString("group_name"))
                },
                assignee = result.getObject("assignee_id", UUID::class.java)?.let {
                    StaffReference(it, result.getString("assignee_name"))
                },
                createdAt = result.getTimestamp("created_at").toInstant(),
                updatedAt = result.getTimestamp("updated_at").toInstant(),
                version = result.getLong("version"),
                isChild = result.getString("kind") == "INTERNAL_CHILD",
                openChildCount = result.getInt("open_child_count"),
                sla = slaBadge(result, result.getString("kind"), now, riskAt),
            )
        }
    }

    override fun search(
        query: String,
        scope: StaffTicketReadScope,
        actorId: UUID,
        filters: StaffTicketSearchFilter,
        sort: String,
        snapshotAt: Instant,
        cursor: StaffTicketSearchCursor?,
        limit: Int,
    ): StaffTicketSearchResult {
        require(scope == StaffTicketReadScope.ALL_TICKETS) { "Unsupported ticket search read policy" }
        require(query.isNotBlank()) { "Search query is required" }
        require(limit in 1..100) { "Search limit must be between 1 and 100" }
        require(sort in SEARCH_SORTS) { "Unsupported ticket search sort" }

        val now = clock.instant()
        val riskAt = now.plusSeconds(30 * 60)
        val conditions = mutableListOf<String>()
        val trimmedQuery = query.trim()
        val parameters = MapSqlParameterSource()
            .addValue("actorId", actorId)
            .addValue("ticketNumberQuery", trimmedQuery.toLongOrNull())
            .addValue("queryText", trimmedQuery)
            .addValue("queryPattern", likeLiteralPattern(trimmedQuery))
            .addValue("limit", limit)
            .addValue("now", Timestamp.from(now))
            .addValue("riskAt", Timestamp.from(riskAt))
            .addValue("snapshotAt", Timestamp.from(snapshotAt))

        // The current product policy grants active staff ALL_TICKETS.  Keep the grant in SQL,
        // rather than assuming an application-layer check remains sufficient if that policy narrows.
        conditions += """
            exists (
                select 1 from staff_accounts authorized_actor
                where authorized_actor.id = :actorId and authorized_actor.status = 'ACTIVE'
            )
        """.trimIndent()
        conditions += "t.updated_at <= :snapshotAt"
        conditions += """
            (
                (cast(:ticketNumberQuery as bigint) is not null
                    and search_document.ticket_number = cast(:ticketNumberQuery as bigint))
                or search_document.staff_document like lower(:queryPattern) escape '\'
            )
        """.trimIndent()
        conditions += compileFilters(filters.toListFilter(), parameters, "search", now, riskAt)
        val whereClause = conditions.joinToString("\n  and ")
        val fromClause = """
            from tickets t
            join ticket_search_documents search_document on search_document.ticket_id = t.id
            left join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
            left join analytics_first_reply_facts fact on fact.ticket_id = t.id
            where $whereClause
        """.trimIndent()
        val scoreExpression = searchScoreExpression()
        val ranked = """
            select ${ticketSummaryColumns()},
                   $scoreExpression as search_score
            $fromClause
        """.trimIndent()

        // Keep the exact count on the same authorization/search predicate, but do not
        // make PostgreSQL evaluate detail-only summary projections for every matching row.
        val resultCount = jdbcTemplate.queryForObject(
            "select count(*) $fromClause",
            parameters,
            Long::class.java,
        ) ?: 0L
        val cursorPredicate = when (sort) {
            SCORE_SORT -> cursor?.let {
                parameters.addValue("cursorScore", checkNotNull(it.lastScore))
                parameters.addValue("cursorTicketNumber", it.lastTicketNumber)
                "where (search_score, ticket_number) < (:cursorScore, :cursorTicketNumber)"
            }.orEmpty()
            UPDATED_SORT -> cursor?.let {
                parameters.addValue("cursorUpdatedAt", Timestamp.from(checkNotNull(it.lastUpdatedAt)))
                parameters.addValue("cursorTicketNumber", it.lastTicketNumber)
                "where (updated_at, ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)"
            }.orEmpty()
            else -> error("Validated above")
        }
        val orderBy = if (sort == SCORE_SORT) {
            "search_score desc, ticket_number desc"
        } else {
            "updated_at desc, ticket_number desc"
        }
        val items = jdbcTemplate.query(
            """
            with ranked as (
                $ranked
            )
            select * from ranked
            $cursorPredicate
            order by $orderBy
            limit :limit
            """.trimIndent(),
            parameters,
        ) { result, _ ->
            StaffTicketSearchHit(
                ticket = ticketSummary(result, now, riskAt),
                score = result.getInt("search_score"),
            )
        }
        return StaffTicketSearchResult(hits = items, resultCount = resultCount)
    }

    override fun listSavedView(
        actorId: UUID,
        conditions: SavedViewConditions,
        filters: StaffTicketListFilter,
        cursor: StaffTicketCursor?,
        limit: Int,
    ): List<StaffTicketSummary> {
        require(limit in 1..101) { "Saved view page limit must be between 1 and 101" }
        SavedViewDefinitionRules.validateConditions(conditions)
        val now = clock.instant()
        val riskAt = now.plusSeconds(30 * 60)
        val parameters = MapSqlParameterSource()
            .addValue("actorId", actorId)
            .addValue("now", Timestamp.from(now))
            .addValue("riskAt", Timestamp.from(riskAt))
            .addValue("limit", limit)
        val where = mutableListOf(authorizedTicketReadPredicate())
        where += compileSavedConditions(conditions, parameters, "view", now, riskAt)
        where += compileFilters(filters, parameters, "filter", now, riskAt)
        cursor?.let {
            where += "(t.updated_at, t.ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)"
            parameters.addValue("cursorUpdatedAt", Timestamp.from(it.updatedAt))
            parameters.addValue("cursorTicketNumber", it.ticketNumber)
        }
        return jdbcTemplate.query(
            """
            select ${ticketSummaryColumns()}
            ${ticketFromClause(where.joinToString("\n  and "))}
            order by t.updated_at desc, t.ticket_number desc
            limit :limit
            """.trimIndent(),
            parameters,
        ) { result, _ -> ticketSummary(result, now, riskAt) }
    }

    override fun countSavedViews(actorId: UUID, views: List<SavedTicketView>): SavedViewCountBatch? {
        val countable = views.take(SavedViewDefinitionRules.MAX_VISIBLE_COUNTED_VIEWS)
        if (countable.isEmpty()) return null
        val now = clock.instant()
        val riskAt = now.plusSeconds(30 * 60)
        val parameters = MapSqlParameterSource().addValue("actorId", actorId)
        val branches = countable.mapIndexed { index, view ->
            val prefix = "count$index"
            parameters.addValue("${prefix}ViewId", view.id)
            parameters.addValue("${prefix}Now", Timestamp.from(now))
            parameters.addValue("${prefix}RiskAt", Timestamp.from(riskAt))
            val where = mutableListOf(authorizedTicketReadPredicate())
            where += compileSavedConditions(
                view.definition.conditions,
                parameters,
                prefix,
                now,
                riskAt,
                nowParameter = "${prefix}Now",
                riskParameter = "${prefix}RiskAt",
            )
            """
            select cast(:${prefix}ViewId as uuid) as view_id, count(*) as ticket_count
            ${ticketFromClause(where.joinToString("\n  and "))}
            """.trimIndent()
        }
        val counts = jdbcTemplate.query(branches.joinToString("\nunion all\n"), parameters) { result, _ ->
            result.getObject("view_id", UUID::class.java) to result.getLong("ticket_count")
        }.toMap()
        return SavedViewCountBatch(counts = counts, asOf = now)
    }

    override fun findDetail(ticketNumber: Long): StaffTicketDetail? {
        val now = clock.instant()
        val riskAt = now.plusSeconds(30 * 60)
        val parameters = MapSqlParameterSource("ticketNumber", ticketNumber)
        val ticketRows = jdbcTemplate.query(
            """
            select t.id, t.ticket_number, t.subject, t.status, t.priority,
                   t.created_at, t.updated_at, t.version, t.kind,
                   (select count(*)
                    from ticket_relations relation
                    join tickets child on child.id = relation.target_ticket_id
                    where relation.source_ticket_id = t.id
                      and relation.relation_type = 'PARENT_CHILD'
                      and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
                   (select count(*)
                    from external_references reference
                    where reference.ticket_id = t.id) as external_reference_count,
                   c.id as customer_id, c.name as customer_name, c.email_display,
                   g.id as group_id, g.name as group_name,
                   s.id as assignee_id, s.display_name as assignee_name,
                   fact.outcome as sla_outcome, fact.due_at as sla_due_at,
                   fact.target_minutes as sla_target_minutes, fact.policy_version as sla_policy_version,
                   fact.schedule_version as sla_schedule_version,
                   linked.direction as related_direction,
                   rt.id as related_id, rt.ticket_number as related_ticket_number,
                   rt.subject as related_subject, rt.status as related_status,
                   rt.priority as related_priority, rt.created_at as related_created_at,
                   rt.updated_at as related_updated_at,
                   rt.version as related_version, rt.kind as related_kind,
                   (select count(*)
                    from ticket_relations open_relation
                    join tickets open_child on open_child.id = open_relation.target_ticket_id
                    where open_relation.source_ticket_id = rt.id
                      and open_relation.relation_type = 'PARENT_CHILD'
                      and open_child.status not in ('SOLVED', 'CLOSED')) as related_open_child_count,
                   rc.id as related_customer_id, rc.name as related_customer_name,
                   rg.id as related_group_id, rg.name as related_group_name,
                   rs.id as related_assignee_id, rs.display_name as related_assignee_name
            from tickets t
            left join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
            left join analytics_first_reply_facts fact on fact.ticket_id = t.id
            left join lateral (
                select 'PARENT' as direction, relation.source_ticket_id as related_ticket_id
                from ticket_relations relation
                where relation.target_ticket_id = t.id
                  and relation.relation_type = 'PARENT_CHILD'
                union all
                select 'CHILD' as direction, relation.target_ticket_id as related_ticket_id
                from ticket_relations relation
                where relation.source_ticket_id = t.id
                  and relation.relation_type = 'PARENT_CHILD'
            ) linked on true
            left join tickets rt on rt.id = linked.related_ticket_id
            left join customers rc on rc.id = rt.requester_id
            left join support_groups rg on rg.id = rt.group_id
            left join staff_accounts rs on rs.id = rt.assignee_id
            where t.ticket_number = :ticketNumber
            order by linked.direction desc, rt.ticket_number
            """.trimIndent(),
            parameters,
        ) { result, _ ->
            val customerId = result.getObject("customer_id", UUID::class.java)
            DetailRow(
                summary = StaffTicketSummary(
                    id = result.getObject("id", UUID::class.java),
                    ticketNumber = result.getLong("ticket_number"),
                    subject = result.getString("subject"),
                    status = TicketStatus.valueOf(result.getString("status")),
                    priority = TicketPriority.valueOf(result.getString("priority")),
                    requester = requesterSummary(result),
                    group = result.getObject("group_id", UUID::class.java)?.let {
                        StaffGroupReference(it, result.getString("group_name"))
                    },
                    assignee = result.getObject("assignee_id", UUID::class.java)?.let {
                        StaffReference(it, result.getString("assignee_name"))
                    },
                    createdAt = result.getTimestamp("created_at").toInstant(),
                    updatedAt = result.getTimestamp("updated_at").toInstant(),
                    version = result.getLong("version"),
                    isChild = result.getString("kind") == "INTERNAL_CHILD",
                    openChildCount = result.getInt("open_child_count"),
                    sla = slaBadge(result, result.getString("kind"), now, riskAt),
                ),
                customer = customerId?.let {
                    StaffTicketCustomer(
                        id = it,
                        displayName = result.getString("customer_name"),
                        email = result.getString("email_display"),
                    )
                },
                related = result.getString("related_direction")?.let { direction ->
                    RelatedTicketRow(
                        direction = direction,
                        ticket = StaffTicketSummary(
                            id = result.getObject("related_id", UUID::class.java),
                            ticketNumber = result.getLong("related_ticket_number"),
                            subject = result.getString("related_subject"),
                            status = TicketStatus.valueOf(result.getString("related_status")),
                            priority = TicketPriority.valueOf(result.getString("related_priority")),
                            requester = requesterSummary(result, "related_customer_id", "related_customer_name"),
                            group = result.getObject("related_group_id", UUID::class.java)?.let {
                                StaffGroupReference(it, result.getString("related_group_name"))
                            },
                            assignee = result.getObject("related_assignee_id", UUID::class.java)?.let {
                                StaffReference(it, result.getString("related_assignee_name"))
                            },
                            createdAt = result.getTimestamp("related_created_at").toInstant(),
                            updatedAt = result.getTimestamp("related_updated_at").toInstant(),
                            version = result.getLong("related_version"),
                            isChild = result.getString("related_kind") == "INTERNAL_CHILD",
                            openChildCount = result.getInt("related_open_child_count"),
                        ),
                    )
                },
                externalReferenceCount = result.getInt("external_reference_count"),
            )
        }
        val ticketRow = ticketRows.firstOrNull() ?: return null

        val ticketParameters = MapSqlParameterSource("ticketId", ticketRow.summary.id)
        val comments = jdbcTemplate.query(
            """
            select tc.id, tc.visibility, tc.author_type, tc.author_id, tc.body, tc.created_at,
                   coalesce(c.name, s.display_name,
                       case tc.author_type
                           when 'SYSTEM' then 'Deskseed'
                           when 'INTEGRATION_CLIENT' then 'IntegrationClient'
                           else '자동화'
                       end) as actor_name
            from ticket_comments tc
            left join customers c on tc.author_type = 'CUSTOMER' and c.id = tc.author_id
            left join staff_accounts s on tc.author_type = 'AGENT' and s.id = tc.author_id
            where tc.ticket_id = :ticketId
            order by tc.created_at, tc.id
            """.trimIndent(),
            ticketParameters,
        ) { result, _ ->
            val authorType = result.getString("author_type")
            StaffCommentView(
                id = result.getObject("id", UUID::class.java),
                visibility = CommentVisibility.valueOf(result.getString("visibility")),
                actor = StaffActorSummary(
                    id = result.getObject("author_id", UUID::class.java),
                    type = if (authorType == "AGENT") "STAFF" else authorType,
                    displayName = result.getString("actor_name"),
                ),
                body = result.getString("body"),
                createdAt = result.getTimestamp("created_at").toInstant(),
                source = when (authorType) {
                    "CUSTOMER" -> "WEB"
                    "AGENT" -> "AGENT_UI"
                    "INTEGRATION_CLIENT" -> "PLATFORM_API"
                    else -> authorType
                },
            )
        }
        val attachmentsByComment = attachmentReadProjection.listForComments(
            comments.map(StaffCommentView::id),
            setOf(AttachmentVisibility.PUBLIC, AttachmentVisibility.INTERNAL),
        )
        val commentsWithAttachments = comments.map { comment ->
            comment.copy(attachments = attachmentsByComment[comment.id].orEmpty())
        }
        val history = jdbcTemplate.query(
            """
            select tae.id, tae.event_type, tae.occurred_at, ta.actor_type, ta.actor_id,
                   coalesce(c.name, s.display_name,
                       case ta.actor_type when 'SYSTEM' then 'Deskseed' else ta.actor_type end) as actor_name
            from ticket_audits ta
            join ticket_audit_events tae on tae.audit_id = ta.id
            left join customers c on ta.actor_type = 'CUSTOMER' and c.id = ta.actor_id
            left join staff_accounts s on ta.actor_type = 'STAFF' and s.id = ta.actor_id
            where ta.ticket_id = :ticketId
            order by tae.occurred_at, ta.id, tae.event_order
            """.trimIndent(),
            ticketParameters,
        ) { result, _ ->
            StaffTicketHistoryItem(
                id = result.getObject("id", UUID::class.java),
                eventType = result.getString("event_type"),
                actor = StaffActorSummary(
                    id = result.getObject("actor_id", UUID::class.java),
                    type = result.getString("actor_type"),
                    displayName = result.getString("actor_name"),
                ),
                occurredAt = result.getTimestamp("occurred_at").toInstant(),
            )
        }

        val related = ticketRows.mapNotNull(DetailRow::related)
        return StaffTicketDetail(
            ticket = ticketRow.summary,
            comments = commentsWithAttachments,
            customer = ticketRow.customer,
            history = history,
            parent = related.firstOrNull { it.direction == "PARENT" }?.ticket,
            children = related.filter { it.direction == "CHILD" }.map(RelatedTicketRow::ticket),
            externalReferenceCount = ticketRow.externalReferenceCount,
        )
    }

    override fun hasRelationReadGrant(ticketId: UUID, actorId: UUID): Boolean =
        jdbcTemplate.queryForObject(
            """
            select exists (
                select 1
                from ticket_relations relation
                join tickets child on child.id = relation.target_ticket_id
                where relation.source_ticket_id = :ticketId
                  and relation.relation_type = 'PARENT_CHILD'
                  and (
                      child.assignee_id = :actorId
                      or exists (
                          select 1
                          from group_memberships membership
                          join support_groups target_group
                            on target_group.id = membership.group_id
                           and target_group.status = 'ACTIVE'
                          where membership.group_id = child.group_id
                            and membership.staff_id = :actorId
                            and membership.status = 'ACTIVE'
                      )
                  )
                union all
                select 1
                from ticket_relations relation
                join tickets parent on parent.id = relation.source_ticket_id
                where relation.target_ticket_id = :ticketId
                  and relation.relation_type = 'PARENT_CHILD'
                  and parent.assignee_id = :actorId
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("ticketId", ticketId)
                .addValue("actorId", actorId),
            Boolean::class.java,
        ) ?: false

    private fun authorizedTicketReadPredicate(): String = """
        exists (
            select 1 from staff_accounts authorized_actor
            where authorized_actor.id = :actorId and authorized_actor.status = 'ACTIVE'
        )
    """.trimIndent()

    private fun ticketFromClause(where: String): String = """
        from tickets t
        left join customers c on c.id = t.requester_id
        left join support_groups g on g.id = t.group_id
        left join staff_accounts s on s.id = t.assignee_id
        left join analytics_first_reply_facts fact on fact.ticket_id = t.id
        where $where
    """.trimIndent()

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

    private fun ticketSummary(result: ResultSet, now: Instant, riskAt: Instant): StaffTicketSummary =
        StaffTicketSummary(
            id = result.getObject("id", UUID::class.java),
            ticketNumber = result.getLong("ticket_number"),
            subject = result.getString("subject"),
            status = TicketStatus.valueOf(result.getString("status")),
            priority = TicketPriority.valueOf(result.getString("priority")),
            requester = requesterSummary(result),
            group = result.getObject("group_id", UUID::class.java)?.let {
                StaffGroupReference(it, result.getString("group_name"))
            },
            assignee = result.getObject("assignee_id", UUID::class.java)?.let {
                StaffReference(it, result.getString("assignee_name"))
            },
            createdAt = result.getTimestamp("created_at").toInstant(),
            updatedAt = result.getTimestamp("updated_at").toInstant(),
            version = result.getLong("version"),
            isChild = result.getString("kind") == "INTERNAL_CHILD",
            openChildCount = result.getInt("open_child_count"),
            sla = slaBadge(result, result.getString("kind"), now, riskAt),
        )

    private fun StaffTicketSearchFilter.toListFilter(): StaffTicketListFilter = StaffTicketListFilter(
        status = status,
        priority = priority,
        groupId = groupId,
        assignee = assignee,
        slaState = slaState,
    )

    private fun compileFilters(
        filters: StaffTicketListFilter,
        parameters: MapSqlParameterSource,
        prefix: String,
        now: Instant,
        riskAt: Instant,
        nowParameter: String = "now",
        riskParameter: String = "riskAt",
    ): List<String> = buildList {
        filters.status?.let {
            val parameter = "${prefix}Status"
            add("t.status = :$parameter")
            parameters.addValue(parameter, it.name)
        }
        filters.priority?.let {
            val parameter = "${prefix}Priority"
            add("t.priority = :$parameter")
            parameters.addValue(parameter, it.name)
        }
        filters.groupId?.let {
            val parameter = "${prefix}GroupId"
            add("t.group_id = :$parameter")
            parameters.addValue(parameter, it)
        }
        filters.assignee?.let { assignee ->
            when (assignee) {
                "me" -> add("t.assignee_id = :actorId")
                "unassigned" -> add("t.assignee_id is null")
                else -> {
                    val parameter = "${prefix}AssigneeId"
                    add("t.assignee_id = :$parameter")
                    parameters.addValue(parameter, UUID.fromString(assignee))
                }
            }
        }
        filters.slaState?.let {
            val parameter = "${prefix}SlaState"
            add("${slaStateExpression(nowParameter, riskParameter)} = :$parameter")
            parameters.addValue(parameter, it.name)
        }
    }

    private fun compileSavedConditions(
        conditions: SavedViewConditions,
        parameters: MapSqlParameterSource,
        prefix: String,
        now: Instant,
        riskAt: Instant,
        nowParameter: String = "now",
        riskParameter: String = "riskAt",
    ): List<String> {
        SavedViewDefinitionRules.validateConditions(conditions)
        val all = conditions.all.mapIndexed { index, condition ->
            compileSavedCondition(condition, parameters, "${prefix}All$index", now, riskAt, nowParameter, riskParameter)
        }
        val any = conditions.any.mapIndexed { index, condition ->
            compileSavedCondition(condition, parameters, "${prefix}Any$index", now, riskAt, nowParameter, riskParameter)
        }
        return buildList {
            if (all.isNotEmpty()) add(all.joinToString(" and "))
            if (any.isNotEmpty()) add("(${any.joinToString(" or ")})")
        }
    }

    private fun compileSavedCondition(
        condition: SavedViewCondition,
        parameters: MapSqlParameterSource,
        parameterPrefix: String,
        now: Instant,
        riskAt: Instant,
        nowParameter: String,
        riskParameter: String,
    ): String = when (condition.field) {
        SavedViewConditionField.STATUS -> when (condition.operator) {
            SavedViewConditionOperator.LESS_THAN_SOLVED -> "t.status not in ('SOLVED', 'CLOSED')"
            else -> enumComparison("t.status", condition, parameters, parameterPrefix)
        }
        SavedViewConditionField.PRIORITY -> enumComparison("t.priority", condition, parameters, parameterPrefix)
        SavedViewConditionField.GROUP -> when (condition.operator) {
            SavedViewConditionOperator.IS_CURRENT_ACTOR_GROUP -> """
                t.group_id in (
                    select membership.group_id
                    from group_memberships membership
                    join support_groups actor_group
                      on actor_group.id = membership.group_id and actor_group.status = 'ACTIVE'
                    where membership.staff_id = :actorId and membership.status = 'ACTIVE'
                )
            """.trimIndent()
            else -> uuidComparison("t.group_id", condition, parameters, parameterPrefix)
        }
        SavedViewConditionField.ASSIGNEE -> when (condition.operator) {
            SavedViewConditionOperator.IS_CURRENT_ACTOR -> "t.assignee_id = :actorId"
            SavedViewConditionOperator.IS_UNASSIGNED -> "t.assignee_id is null"
            else -> uuidComparison("t.assignee_id", condition, parameters, parameterPrefix)
        }
        SavedViewConditionField.FIRST_REPLY_SLA_STATE -> enumComparison(
            slaStateExpression(nowParameter, riskParameter),
            condition,
            parameters,
            parameterPrefix,
        )
        SavedViewConditionField.TICKET_KIND -> enumComparison("t.kind", condition, parameters, parameterPrefix)
        SavedViewConditionField.UPDATED_AT -> {
            val days = condition.values.single().toLong()
            val parameter = "${parameterPrefix}Since"
            parameters.addValue(parameter, Timestamp.from(now.minusSeconds(days * 24 * 60 * 60)))
            "t.updated_at >= :$parameter"
        }
    }

    private fun enumComparison(
        column: String,
        condition: SavedViewCondition,
        parameters: MapSqlParameterSource,
        parameterPrefix: String,
    ): String {
        val parameter = "${parameterPrefix}Values"
        parameters.addValue(
            parameter,
            if (condition.operator in setOf(SavedViewConditionOperator.EQUALS, SavedViewConditionOperator.NOT_EQUALS)) {
                condition.values.single()
            } else {
                condition.values
            },
        )
        return when (condition.operator) {
            SavedViewConditionOperator.EQUALS -> "$column = :$parameter"
            SavedViewConditionOperator.NOT_EQUALS -> "$column <> :$parameter"
            SavedViewConditionOperator.IN -> "$column in (:$parameter)"
            SavedViewConditionOperator.NOT_IN -> "$column not in (:$parameter)"
            else -> throw IllegalArgumentException("Invalid saved view enum comparison")
        }
    }

    private fun uuidComparison(
        column: String,
        condition: SavedViewCondition,
        parameters: MapSqlParameterSource,
        parameterPrefix: String,
    ): String {
        val parameter = "${parameterPrefix}Values"
        val values = condition.values.map(UUID::fromString)
        parameters.addValue(
            parameter,
            if (condition.operator in setOf(SavedViewConditionOperator.EQUALS, SavedViewConditionOperator.NOT_EQUALS)) {
                values.single()
            } else {
                values
            },
        )
        return when (condition.operator) {
            SavedViewConditionOperator.EQUALS -> "$column = :$parameter"
            SavedViewConditionOperator.NOT_EQUALS -> "$column <> :$parameter"
            SavedViewConditionOperator.IN -> "$column in (:$parameter)"
            SavedViewConditionOperator.NOT_IN -> "$column not in (:$parameter)"
            else -> throw IllegalArgumentException("Invalid saved view UUID comparison")
        }
    }

    private fun slaStateExpression(nowParameter: String, riskParameter: String): String = """
        (case
            when fact.outcome = 'ACTIVE' and fact.due_at <= :$nowParameter then 'BREACHED'
            when fact.outcome = 'ACTIVE' and fact.due_at <= :$riskParameter then 'AT_RISK'
            when fact.outcome is null and t.kind = 'CUSTOMER_REQUEST' then 'NO_POLICY'
            else fact.outcome
        end)
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

    private fun likeLiteralPattern(query: String): String = "%${
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }%"

    private data class DetailRow(
        val summary: StaffTicketSummary,
        val customer: StaffTicketCustomer?,
        val related: RelatedTicketRow?,
        val externalReferenceCount: Int,
    )

    private fun requesterSummary(
        result: ResultSet,
        idColumn: String = "customer_id",
        nameColumn: String = "customer_name",
    ): StaffActorSummary {
        val customerId = result.getObject(idColumn, UUID::class.java)
        return if (customerId == null) {
            StaffActorSummary(null, "INTEGRATION_CLIENT", "내부 작업")
        } else {
            StaffActorSummary(customerId, "CUSTOMER", result.getString(nameColumn))
        }
    }

    private data class RelatedTicketRow(
        val direction: String,
        val ticket: StaffTicketSummary,
    )

    private fun slaBadge(
        result: java.sql.ResultSet,
        kind: String,
        now: Instant,
        riskAt: Instant,
    ): StaffSlaBadge? {
        val outcome = result.getString("sla_outcome")
        if (outcome == null && kind != "CUSTOMER_REQUEST") return null
        val dueAt = result.getTimestamp("sla_due_at")?.toInstant()
        val state = classifyFirstReplySlaState(outcome, dueAt, now, riskAt)
        return StaffSlaBadge(
            state = state,
            dueAt = dueAt,
            targetMinutes = result.getLong("sla_target_minutes").takeUnless { result.wasNull() },
            policyVersion = result.getInt("sla_policy_version").takeUnless { result.wasNull() },
            scheduleVersion = result.getInt("sla_schedule_version").takeUnless { result.wasNull() },
        )
    }

    private companion object {
        const val UPDATED_SORT = "updatedAt:desc,ticketNumber:desc"
        const val SCORE_SORT = "score:desc,ticketNumber:desc"
        val SEARCH_SORTS = setOf(UPDATED_SORT, SCORE_SORT)
    }
}

internal fun classifyFirstReplySlaState(
    outcome: String?,
    dueAt: Instant?,
    now: Instant,
    riskAt: Instant,
): StaffSlaDisplayState = when {
    outcome == null || outcome == "NO_POLICY" -> StaffSlaDisplayState.NO_POLICY
    outcome == "ACTIVE" && dueAt?.let { !it.isAfter(now) } == true -> StaffSlaDisplayState.BREACHED
    outcome == "ACTIVE" && dueAt?.let { !it.isAfter(riskAt) } == true -> StaffSlaDisplayState.AT_RISK
    else -> StaffSlaDisplayState.valueOf(outcome)
}
