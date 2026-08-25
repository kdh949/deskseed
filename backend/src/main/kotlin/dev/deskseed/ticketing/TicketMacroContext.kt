package dev.deskseed.ticketing

import java.util.UUID

data class TicketMacroContext(
    val ticketId: UUID,
    val ticketNumber: Long,
    val ticketKind: TicketKind,
    val status: TicketStatus,
    val version: Long,
)

/** Minimal current-row context needed to validate a macro preview or apply plan. */
interface TicketMacroContextQuery {
    fun find(ticketNumber: Long): TicketMacroContext?
}
