package dev.deskseed.macro.internal

import dev.deskseed.ticketing.TicketMacroActivationGuard
import dev.deskseed.ticketing.TicketMacroVersionUnavailableException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JdbcTicketMacroActivationGuard(
    private val jdbc: JdbcTemplate,
) : TicketMacroActivationGuard {
    override fun requireActive(macroId: UUID, macroVersion: Int, actorStaffId: UUID) {
        val active = jdbc.queryForObject(
            """
            select exists(
                select 1
                  from macro_definitions
                 where id = ?
                   and active_version = ?
                   and (scope = 'SHARED' or (scope = 'PERSONAL' and owner_staff_id = ?))
            )
            """.trimIndent(),
            Boolean::class.java,
            macroId,
            macroVersion,
            actorStaffId,
        ) == true
        if (!active) throw TicketMacroVersionUnavailableException()
    }
}
