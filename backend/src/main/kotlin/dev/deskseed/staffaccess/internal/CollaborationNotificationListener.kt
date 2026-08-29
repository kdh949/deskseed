package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.CollaborationNotificationCreatedMessage
import dev.deskseed.collaboration.CollaborationRealtimeGateway
import dev.deskseed.collaboration.StaffNotificationCreated
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
internal class CollaborationNotificationListener(
    private val gateway: CollaborationRealtimeGateway,
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterNotificationCommit(event: StaffNotificationCreated) {
        gateway.sendToStaff(
            event.recipientStaffId,
            CollaborationNotificationCreatedMessage(event.notificationId, event.occurredAt),
        )
    }
}
