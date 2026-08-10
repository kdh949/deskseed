package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.PublicCommentView
import dev.deskseed.ticketing.PublicTicketView
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
internal class PublicTicketQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun find(ticketId: UUID, ticketNumber: Long): PublicTicketView? {
        val ticket = jdbcTemplate.query(
            """
            select ticket_number, subject,
                   case status
                       when 'ON_HOLD' then 'OPEN'
                       when 'CLOSED' then 'SOLVED'
                       else status
                   end as customer_status,
                   created_at, updated_at
            from tickets
            where id = ?
              and ticket_number = ?
              and kind = 'CUSTOMER_REQUEST'
            """.trimIndent(),
            { result, _ ->
                PublicTicketView(
                    ticketNumber = result.getLong("ticket_number"),
                    subject = result.getString("subject"),
                    status = CustomerRequestStatus.valueOf(result.getString("customer_status")),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                    updatedAt = result.getTimestamp("updated_at").toInstant(),
                    comments = emptyList(),
                )
            },
            ticketId,
            ticketNumber,
        ).firstOrNull() ?: return null

        val comments = jdbcTemplate.query(
            """
            select id,
                   case author_type
                       when 'CUSTOMER' then '고객'
                       when 'AGENT' then '상담팀'
                       else 'Deskseed'
                   end as author_display_name,
                   body, created_at
            from ticket_comments
            where ticket_id = ?
              and visibility = 'PUBLIC'
            order by created_at, id
            """.trimIndent(),
            { result, _ ->
                PublicCommentView(
                    id = result.getObject("id", UUID::class.java),
                    authorDisplayName = result.getString("author_display_name"),
                    body = result.getString("body"),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                )
            },
            ticketId,
        )

        return ticket.copy(comments = comments)
    }
}
