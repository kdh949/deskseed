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
            )
        }
    }

    override fun findDetail(ticketNumber: Long): StaffTicketDetail? {
        val parameters = MapSqlParameterSource("ticketNumber", ticketNumber)
        val ticketRow = jdbcTemplate.query(
            """
            select t.id, t.ticket_number, t.subject, t.status, t.priority,
                   t.updated_at, t.version, t.kind,
                   c.id as customer_id, c.name as customer_name, c.email_display,
                   g.id as group_id, g.name as group_name,
                   s.id as assignee_id, s.display_name as assignee_name
            from tickets t
            join customers c on c.id = t.requester_id
            left join support_groups g on g.id = t.group_id
            left join staff_accounts s on s.id = t.assignee_id
            where t.ticket_number = :ticketNumber
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
                ),
                customer = StaffTicketCustomer(
                    id = customerId,
                    displayName = result.getString("customer_name"),
                    email = result.getString("email_display"),
                ),
            )
        }.firstOrNull() ?: return null

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

        return StaffTicketDetail(ticketRow.summary, comments, ticketRow.customer, history)
    }

    private data class DetailRow(
        val summary: StaffTicketSummary,
        val customer: StaffTicketCustomer,
    )
}
