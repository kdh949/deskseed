package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketMacroContext
import dev.deskseed.ticketing.TicketMacroContextQuery
import dev.deskseed.ticketing.TicketStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JdbcTicketMacroContextQuery(
    private val jdbc: JdbcTemplate,
) : TicketMacroContextQuery {
    override fun find(ticketNumber: Long): TicketMacroContext? = jdbc.query(
        """
        select id, ticket_number, kind, status, version
          from tickets
         where ticket_number = ?
        """.trimIndent(),
        { result, _ ->
            TicketMacroContext(
                ticketId = result.getObject("id", UUID::class.java),
                ticketNumber = result.getLong("ticket_number"),
                ticketKind = TicketKind.valueOf(result.getString("kind")),
                status = TicketStatus.valueOf(result.getString("status")),
                version = result.getLong("version"),
            )
        },
        ticketNumber,
    ).singleOrNull()
}
