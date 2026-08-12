package dev.deskseed.integration.internal

import dev.deskseed.integration.ExternalReferenceConflictException
import dev.deskseed.integration.ExternalSystemConflictException
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
internal class ExternalReferenceCapacityGuard(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun requireExternalSystemCapacity() {
        acquireTransactionLock(EXTERNAL_SYSTEM_LOCK_KEY)
        if (count("select count(*) from external_systems") >= MAX_EXTERNAL_SYSTEMS) {
            throw ExternalSystemConflictException("EXTERNAL_SYSTEM_LIMIT_REACHED")
        }
    }

    fun requireTicketReferenceCapacity(ticketId: UUID) {
        acquireTransactionLock(ticketId.mostSignificantBits xor ticketId.leastSignificantBits xor TICKET_LOCK_SALT)
        val count = jdbcTemplate.queryForObject(
            "select count(*) from external_references where ticket_id = ?",
            Long::class.java,
            ticketId,
        ) ?: 0
        if (count >= MAX_REFERENCES_PER_TICKET) {
            throw ExternalReferenceConflictException("EXTERNAL_REFERENCE_LIMIT_REACHED")
        }
    }

    private fun acquireTransactionLock(key: Long) {
        jdbcTemplate.execute(
            ConnectionCallback { connection ->
                connection.prepareStatement("select pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, key)
                    statement.execute()
                }
            },
        )
    }

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java) ?: 0

    private companion object {
        const val MAX_EXTERNAL_SYSTEMS = 100L
        const val MAX_REFERENCES_PER_TICKET = 100L
        const val EXTERNAL_SYSTEM_LOCK_KEY = 0x44534B4558545359L
        const val TICKET_LOCK_SALT = 0x44534B4558545246L
    }
}
