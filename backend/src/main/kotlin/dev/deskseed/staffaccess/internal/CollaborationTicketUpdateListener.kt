package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.CollaborationRealtimeGateway
import dev.deskseed.collaboration.CollaborationTicketUpdated
import dev.deskseed.collaboration.CollaborationTicketUpdatedMessage
import dev.deskseed.ticketing.TicketCollaborationUpdated
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/** Emits an advisory, metadata-only signal only after the ticket transaction has committed. */
@Component
internal class CollaborationTicketUpdateListener(
    private val gateway: CollaborationRealtimeGateway,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterTicketCommit(event: TicketCollaborationUpdated) {
        gateway.broadcast(
            event.ticketNumber,
            CollaborationTicketUpdatedMessage(
                CollaborationTicketUpdated(
                    ticketNumber = event.ticketNumber,
                    ticketVersion = event.ticketVersion,
                    changedFields = event.changedFields,
                    actorStaffId = event.actorStaffId,
                    occurredAt = event.occurredAt,
                ),
            ),
        )
    }
}
