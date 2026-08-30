package dev.deskseed.collaboration

import java.time.Instant
import java.util.UUID

enum class TicketPresenceState {
    VIEWING,
    EDITING_PUBLIC,
    EDITING_INTERNAL,
    AWAY,
}

enum class TicketPresenceChangeAction {
    JOINED,
    UPDATED,
    LEFT,
    EXPIRED,
}

data class TicketPresenceMember(
    val staffId: UUID,
    val displayName: String,
    val state: TicketPresenceState,
    val lastSeenAt: Instant,
)

data class TicketPresenceChange(
    val ticketNumber: Long,
    val action: TicketPresenceChangeAction,
    val member: TicketPresenceMember,
)

data class TicketPresenceSnapshot(
    val ticketNumber: Long,
    val members: List<TicketPresenceMember>,
)

data class PresenceConnection(
    val connectionId: String,
    val staffId: UUID,
    val displayName: String,
) {
    init {
        require(connectionId.length in 1..128 && connectionId.none(Char::isISOControl)) {
            "connectionId must be bounded and control-character free"
        }
        require(displayName.length in 1..120 && displayName.none(Char::isISOControl)) {
            "displayName must be bounded and control-character free"
        }
    }
}

/**
 * Advisory-only, short-lived presence port. It never grants ticket access, locks a ticket,
 * or replaces the normal optimistic ticket command version.
 */
interface PresenceBus {
    fun subscribe(connection: PresenceConnection, ticketNumber: Long): PresenceSubscription

    fun updateState(
        connectionId: String,
        ticketNumber: Long,
        state: TicketPresenceState,
    ): TicketPresenceChange?

    fun heartbeat(connectionId: String): List<TicketPresenceChange>

    fun unsubscribe(connectionId: String, ticketNumber: Long): TicketPresenceChange?

    fun disconnect(connectionId: String): List<TicketPresenceChange>

    fun expireStale(): List<TicketPresenceChange>
}

data class PresenceSubscription(
    val snapshot: TicketPresenceSnapshot,
    val change: TicketPresenceChange?,
)

sealed interface CollaborationRealtimeMessage {
    val ticketNumber: Long?
}

data class CollaborationPresenceSnapshotMessage(
    val snapshot: TicketPresenceSnapshot,
) : CollaborationRealtimeMessage {
    override val ticketNumber: Long = snapshot.ticketNumber
}

data class CollaborationPresenceDeltaMessage(
    val change: TicketPresenceChange,
) : CollaborationRealtimeMessage {
    override val ticketNumber: Long = change.ticketNumber
}

data class CollaborationTicketUpdatedMessage(
    val update: CollaborationTicketUpdated,
) : CollaborationRealtimeMessage {
    override val ticketNumber: Long = update.ticketNumber
}

data class CollaborationNotificationCreatedMessage(
    val notificationId: UUID,
    val occurredAt: Instant,
) : CollaborationRealtimeMessage {
    override val ticketNumber: Long? = null
}

data class CollaborationRealtimeErrorMessage(
    val code: CollaborationRealtimeErrorCode,
    val retryable: Boolean,
    val retryAfterMillis: Long? = null,
) : CollaborationRealtimeMessage {
    override val ticketNumber: Long? = null
}

enum class CollaborationRealtimeErrorCode {
    UNAUTHORIZED,
    FORBIDDEN,
    INVALID_MESSAGE,
    MESSAGE_TOO_LARGE,
    RATE_LIMITED,
    TRANSIENT_UNAVAILABLE,
}

data class CollaborationTicketUpdated(
    val ticketNumber: Long,
    val ticketVersion: Long,
    val changedFields: Set<String>,
    val actorStaffId: UUID?,
    val occurredAt: Instant,
) {
    init {
        require(ticketNumber > 0) { "ticketNumber must be positive" }
        require(ticketVersion > 0) { "ticketVersion must be positive" }
        require(changedFields.size <= 32 && changedFields.all(FIELD_NAME::matches)) {
            "changedFields must be safe bounded names"
        }
    }

    private companion object {
        val FIELD_NAME = Regex("[a-z][a-zA-Z0-9]{0,63}")
    }
}

/** Sends already-authorized messages only to sessions subscribed to a ticket. */
interface CollaborationRealtimeGateway {
    fun register(
        connection: PresenceConnection,
        sender: (CollaborationRealtimeMessage) -> Unit,
        closer: () -> Unit,
    )

    fun subscribe(connectionId: String, ticketNumber: Long)

    fun unsubscribe(connectionId: String, ticketNumber: Long)

    fun unregister(connectionId: String)

    fun send(connectionId: String, message: CollaborationRealtimeMessage)

    fun broadcast(ticketNumber: Long, message: CollaborationRealtimeMessage)

    fun sendToStaff(staffId: UUID, message: CollaborationRealtimeMessage)
}

/** Lets staff-session revocation close only the affected in-memory realtime connections. */
interface CollaborationPresenceConnectionTerminator {
    fun closeStaffConnections(staffId: UUID)
}
