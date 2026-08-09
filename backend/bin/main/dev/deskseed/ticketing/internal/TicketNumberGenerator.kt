package dev.deskseed.ticketing.internal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
internal class TicketNumberGenerator(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun next(): Long = jdbcTemplate.queryForObject(
        "select nextval('ticket_number_seq')",
        Long::class.java,
    ) ?: error("ticket_number_seq did not return a value")
}
