package dev.deskseed.ticketing.internal

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
import dev.deskseed.ticketing.StaffTicketSummary
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID

@Repository
internal class StaffTicketQueryRepository(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) : StaffTicketReadStore {
    override fun list(
        view: DefaultStaffView,
        actorId: UUID,
        filters: StaffTicketListFilter,
        cursor: StaffTicketCursor?,
        limit: Int,
        recentlySolvedAfter: Instant,
    ): List<StaffTicketSummary> {
        val conditions = mutableListOf<String>()
        val parameters = MapSqlParameterSource()
            .addValue("actorId", actorId)
            .addValue("recentlySolvedAfter", Timestamp.from(recentlySolvedAfter))
            .addValue("limit", limit)

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
        cursor?.let {
            conditions += "(t.updated_at, t.ticket_number) < (:cursorUpdatedAt, :cursorTicketNumber)"
            parameters.addValue("cursorUpdatedAt", Timestamp.from(it.updatedAt))
            parameters.addValue("cursorTicketNumber", it.ticketNumber)
        }

        return jdbcTemplate.query(
            """
            select t.id, t.ticket_number, t.subject, t.status, t.priority,
                   t.updated_at, t.version, t.kind,
                   (select count(*)
                    from ticket_relations relation
                    join tickets child on child.id = relation.target_ticket_id
                    where relation.source_ticket_id = t.id
                      and relation.relation_type = 'PARENT_CHILD'
                      and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
                   c.id as customer_id, c.name as customer_name,
                   g.id as group_id, g.name as group_name,
                   s.id as assignee_id, s.display_name as assignee_name
            from tickets t
            join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
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
                requester = StaffActorSummary(
                    id = result.getObject("customer_id", UUID::class.java),
                    type = "CUSTOMER",
                    displayName = result.getString("customer_name"),
                ),
                group = result.getObject("group_id", UUID::class.java)?.let {
                    StaffGroupReference(it, result.getString("group_name"))
                },
                assignee = result.getObject("assignee_id", UUID::class.java)?.let {
                    StaffReference(it, result.getString("assignee_name"))
                },
                updatedAt = result.getTimestamp("updated_at").toInstant(),
                version = result.getLong("version"),
                isChild = result.getString("kind") == "INTERNAL_CHILD",
                openChildCount = result.getInt("open_child_count"),
            )
        }
    }

    override fun findDetail(ticketNumber: Long): StaffTicketDetail? {
        val parameters = MapSqlParameterSource("ticketNumber", ticketNumber)
        val ticketRows = jdbcTemplate.query(
            """
            select t.id, t.ticket_number, t.subject, t.status, t.priority,
                   t.updated_at, t.version, t.kind,
                   (select count(*)
                    from ticket_relations relation
                    join tickets child on child.id = relation.target_ticket_id
                    where relation.source_ticket_id = t.id
                      and relation.relation_type = 'PARENT_CHILD'
                      and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
                   c.id as customer_id, c.name as customer_name, c.email_display,
                   g.id as group_id, g.name as group_name,
                   s.id as assignee_id, s.display_name as assignee_name,
                   linked.direction as related_direction,
                   rt.id as related_id, rt.ticket_number as related_ticket_number,
                   rt.subject as related_subject, rt.status as related_status,
                   rt.priority as related_priority, rt.updated_at as related_updated_at,
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
            join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
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
                    requester = StaffActorSummary(customerId, "CUSTOMER", result.getString("customer_name")),
                    group = result.getObject("group_id", UUID::class.java)?.let {
                        StaffGroupReference(it, result.getString("group_name"))
                    },
                    assignee = result.getObject("assignee_id", UUID::class.java)?.let {
                        StaffReference(it, result.getString("assignee_name"))
                    },
                    updatedAt = result.getTimestamp("updated_at").toInstant(),
                    version = result.getLong("version"),
                    isChild = result.getString("kind") == "INTERNAL_CHILD",
                    openChildCount = result.getInt("open_child_count"),
                ),
                customer = StaffTicketCustomer(
                    id = customerId,
                    displayName = result.getString("customer_name"),
                    email = result.getString("email_display"),
                ),
                related = result.getString("related_direction")?.let { direction ->
                    RelatedTicketRow(
                        direction = direction,
                        ticket = StaffTicketSummary(
                            id = result.getObject("related_id", UUID::class.java),
                            ticketNumber = result.getLong("related_ticket_number"),
                            subject = result.getString("related_subject"),
                            status = TicketStatus.valueOf(result.getString("related_status")),
                            priority = TicketPriority.valueOf(result.getString("related_priority")),
                            requester = StaffActorSummary(
                                result.getObject("related_customer_id", UUID::class.java),
                                "CUSTOMER",
                                result.getString("related_customer_name"),
                            ),
                            group = result.getObject("related_group_id", UUID::class.java)?.let {
                                StaffGroupReference(it, result.getString("related_group_name"))
                            },
                            assignee = result.getObject("related_assignee_id", UUID::class.java)?.let {
                                StaffReference(it, result.getString("related_assignee_name"))
                            },
                            updatedAt = result.getTimestamp("related_updated_at").toInstant(),
                            version = result.getLong("related_version"),
                            isChild = result.getString("related_kind") == "INTERNAL_CHILD",
                            openChildCount = result.getInt("related_open_child_count"),
                        ),
                    )
                },
            )
        }
        val ticketRow = ticketRows.firstOrNull() ?: return null

        val ticketParameters = MapSqlParameterSource("ticketId", ticketRow.summary.id)
        val comments = jdbcTemplate.query(
            """
            select tc.id, tc.visibility, tc.author_type, tc.author_id, tc.body, tc.created_at,
                   coalesce(c.name, s.display_name,
                       case tc.author_type when 'SYSTEM' then 'Deskseed' else '자동화' end) as actor_name
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
                    else -> authorType
                },
            )
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
            comments = comments,
            customer = ticketRow.customer,
            history = history,
            parent = related.firstOrNull { it.direction == "PARENT" }?.ticket,
            children = related.filter { it.direction == "CHILD" }.map(RelatedTicketRow::ticket),
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

    private data class DetailRow(
        val summary: StaffTicketSummary,
        val customer: StaffTicketCustomer,
        val related: RelatedTicketRow?,
    )

    private data class RelatedTicketRow(
        val direction: String,
        val ticket: StaffTicketSummary,
    )
}
