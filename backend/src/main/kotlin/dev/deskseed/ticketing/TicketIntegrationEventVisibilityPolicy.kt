package dev.deskseed.ticketing

import dev.deskseed.eventpublication.DomainEventVisibility

/** Public event visibility is derived from ticket kind, never from the caller. */
object TicketIntegrationEventVisibilityPolicy {
    fun forTicket(kind: TicketKind): DomainEventVisibility = when (kind) {
        TicketKind.CUSTOMER_REQUEST,
        TicketKind.AGENT_CREATED,
        -> DomainEventVisibility.PUBLIC

        TicketKind.INTERNAL_CHILD,
        TicketKind.INTERNAL_WORK_ITEM,
        -> DomainEventVisibility.INTERNAL
    }
}
