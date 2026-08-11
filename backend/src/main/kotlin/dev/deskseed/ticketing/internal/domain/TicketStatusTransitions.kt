package dev.deskseed.ticketing.internal.domain

import dev.deskseed.ticketing.TicketStatus

internal object TicketStatusTransitions {
    private val allowed: Map<TicketStatus, Set<TicketStatus>> = mapOf(
        TicketStatus.NEW to setOf(TicketStatus.OPEN, TicketStatus.PENDING, TicketStatus.SOLVED),
        TicketStatus.OPEN to setOf(TicketStatus.PENDING, TicketStatus.ON_HOLD, TicketStatus.SOLVED),
        TicketStatus.PENDING to setOf(TicketStatus.OPEN, TicketStatus.SOLVED),
        TicketStatus.ON_HOLD to setOf(TicketStatus.OPEN, TicketStatus.SOLVED),
        TicketStatus.SOLVED to setOf(TicketStatus.OPEN),
        TicketStatus.CLOSED to emptySet(),
    )

    fun isAllowed(from: TicketStatus, to: TicketStatus): Boolean = from == to || to in allowed.getValue(from)
}
