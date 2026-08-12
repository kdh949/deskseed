package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.CustomerTicketPage
import dev.deskseed.ticketing.CustomerTicketPageQuery
import dev.deskseed.ticketing.CustomerTicketSummary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp

@Repository
internal class CustomerTicketQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun list(query: CustomerTicketPageQuery): CustomerTicketPage {
        val predicates = mutableListOf(
            "requester_id = ?",
            "kind = 'CUSTOMER_REQUEST'",
        )
        val parameters = mutableListOf<Any>(query.requesterId)
        if (query.status != null) {
            predicates += "case status when 'ON_HOLD' then 'OPEN' when 'CLOSED' then 'SOLVED' else status end = ?"
            parameters += query.status.name
        }
        if (query.beforeUpdatedAt != null && query.beforeTicketNumber != null) {
            predicates += "(updated_at, ticket_number) < (?, ?)"
            parameters += Timestamp.from(query.beforeUpdatedAt)
            parameters += query.beforeTicketNumber
        }
        parameters += query.limit + 1
        val rows = jdbcTemplate.query(
            """
            select ticket_number, subject,
                   case status
                       when 'ON_HOLD' then 'OPEN'
                       when 'CLOSED' then 'SOLVED'
                       else status
                   end as customer_status,
                   created_at, updated_at
            from tickets
            where ${predicates.joinToString(" and ")}
            order by updated_at desc, ticket_number desc
            limit ?
            """.trimIndent(),
            { result, _ ->
                CustomerTicketSummary(
                    ticketNumber = result.getLong("ticket_number"),
                    subject = result.getString("subject"),
                    status = CustomerRequestStatus.valueOf(result.getString("customer_status")),
                    createdAt = result.getTimestamp("created_at").toInstant(),
                    updatedAt = result.getTimestamp("updated_at").toInstant(),
                )
            },
            *parameters.toTypedArray(),
        )
        return CustomerTicketPage(rows.take(query.limit), rows.size > query.limit)
    }
}
