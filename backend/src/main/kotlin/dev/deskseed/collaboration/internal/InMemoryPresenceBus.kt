package dev.deskseed.collaboration.internal

import dev.deskseed.collaboration.CollaborationPresenceConnectionTerminator
import dev.deskseed.collaboration.CollaborationRealtimeGateway
import dev.deskseed.collaboration.CollaborationRealtimeMessage
import dev.deskseed.collaboration.CollaborationPresenceDeltaMessage
import dev.deskseed.collaboration.PresenceBus
import dev.deskseed.collaboration.PresenceConnection
import dev.deskseed.collaboration.PresenceSubscription
import dev.deskseed.collaboration.TicketPresenceChange
import dev.deskseed.collaboration.TicketPresenceChangeAction
import dev.deskseed.collaboration.TicketPresenceMember
import dev.deskseed.collaboration.TicketPresenceSnapshot
import dev.deskseed.collaboration.TicketPresenceState
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Single-node advisory presence registry. PostgreSQL ticket commands and versions remain
 * the correctness boundary; this data is intentionally process-local and short-lived.
 */
@Service
internal class InMemoryPresenceBus(
    private val clock: Clock,
    @Value("\${deskseed.collaboration.presence.stale-after:60s}") private val staleAfter: Duration,
) : PresenceBus {
    private val entries = mutableMapOf<String, PresenceEntry>()

    init {
        require(staleAfter in MIN_STALE_AFTER..MAX_STALE_AFTER) {
            "deskseed.collaboration.presence.stale-after must be between 30s and 5m"
        }
    }

    @Synchronized
    override fun subscribe(connection: PresenceConnection, ticketNumber: Long): PresenceSubscription {
        require(ticketNumber > 0) { "ticketNumber must be positive" }
        val before = aggregated(ticketNumber)
        entries[entryKey(connection.connectionId, ticketNumber)] = PresenceEntry(
            connection = connection,
            ticketNumber = ticketNumber,
            state = TicketPresenceState.VIEWING,
            lastSeenAt = Instant.now(clock),
        )
        val after = aggregated(ticketNumber)
        return PresenceSubscription(
            snapshot = TicketPresenceSnapshot(ticketNumber, after.values.sortedBy { it.staffId }),
            change = change(ticketNumber, before, after, TicketPresenceChangeAction.JOINED),
        )
    }

    @Synchronized
    override fun updateState(
        connectionId: String,
        ticketNumber: Long,
        state: TicketPresenceState,
    ): TicketPresenceChange? {
        val key = entryKey(connectionId, ticketNumber)
        val existing = entries[key] ?: return null
        val before = aggregated(ticketNumber)
        entries[key] = existing.copy(state = state, lastSeenAt = Instant.now(clock))
        return change(ticketNumber, before, aggregated(ticketNumber), TicketPresenceChangeAction.UPDATED)
    }

    @Synchronized
    override fun heartbeat(connectionId: String): List<TicketPresenceChange> {
        val now = Instant.now(clock)
        entries.entries
            .filter { it.value.connection.connectionId == connectionId }
            .forEach { (key, entry) -> entries[key] = entry.copy(lastSeenAt = now) }
        return emptyList()
    }

    @Synchronized
    override fun unsubscribe(connectionId: String, ticketNumber: Long): TicketPresenceChange? {
        val before = aggregated(ticketNumber)
        val removed = entries.remove(entryKey(connectionId, ticketNumber)) ?: return null
        return change(
            ticketNumber,
            before,
            aggregated(ticketNumber),
            TicketPresenceChangeAction.LEFT,
            removed.connection.staffId,
        )
    }

    @Synchronized
    override fun disconnect(connectionId: String): List<TicketPresenceChange> =
        entries.values
            .filter { it.connection.connectionId == connectionId }
            .map { it.ticketNumber }
            .distinct()
            .mapNotNull { ticketNumber -> unsubscribe(connectionId, ticketNumber) }

    @Synchronized
    override fun expireStale(): List<TicketPresenceChange> {
        val now = Instant.now(clock)
        return entries.values
            .filter { !it.lastSeenAt.plus(staleAfter).isAfter(now) }
            .map { it.ticketNumber }
            .distinct()
            .flatMap { ticketNumber ->
                val before = aggregated(ticketNumber)
                val expiredStaffIds = entries.values
                    .filter { it.ticketNumber == ticketNumber && !it.lastSeenAt.plus(staleAfter).isAfter(now) }
                    .map { it.connection.staffId }
                    .toSet()
                entries.entries.removeIf { (_, entry) ->
                    entry.ticketNumber == ticketNumber && !entry.lastSeenAt.plus(staleAfter).isAfter(now)
                }
                val after = aggregated(ticketNumber)
                expiredStaffIds.mapNotNull { staffId ->
                    change(ticketNumber, before, after, TicketPresenceChangeAction.EXPIRED, staffId)
                }
            }
    }

    private fun aggregated(ticketNumber: Long): Map<UUID, TicketPresenceMember> =
        entries.values
            .filter { it.ticketNumber == ticketNumber }
            .groupBy { it.connection.staffId }
            .mapValues { (_, staffEntries) ->
                val mostActive = staffEntries.maxWith(
                    compareBy<PresenceEntry> { statePriority(it.state) }.thenBy { it.lastSeenAt },
                )
                TicketPresenceMember(
                    staffId = mostActive.connection.staffId,
                    displayName = mostActive.connection.displayName,
                    state = mostActive.state,
                    lastSeenAt = staffEntries.maxOf { it.lastSeenAt },
                )
            }

    private fun change(
        ticketNumber: Long,
        before: Map<UUID, TicketPresenceMember>,
        after: Map<UUID, TicketPresenceMember>,
        departedAction: TicketPresenceChangeAction,
        affectedStaffId: UUID? = null,
    ): TicketPresenceChange? {
        val staffId = affectedStaffId ?: (after.keys - before.keys).singleOrNull()
            ?: (after.keys intersect before.keys).singleOrNull { before[it] != after[it] }
            ?: return null
        val beforeMember = before[staffId]
        val afterMember = after[staffId]
        val action = when {
            beforeMember == null && afterMember != null -> TicketPresenceChangeAction.JOINED
            beforeMember != null && afterMember == null -> departedAction
            beforeMember != afterMember -> TicketPresenceChangeAction.UPDATED
            else -> return null
        }
        return TicketPresenceChange(ticketNumber, action, afterMember ?: beforeMember!!)
    }

    private fun entryKey(connectionId: String, ticketNumber: Long) = "$connectionId:$ticketNumber"

    private fun statePriority(state: TicketPresenceState): Int = when (state) {
        TicketPresenceState.EDITING_PUBLIC, TicketPresenceState.EDITING_INTERNAL -> 3
        TicketPresenceState.VIEWING -> 2
        TicketPresenceState.AWAY -> 1
    }

    private data class PresenceEntry(
        val connection: PresenceConnection,
        val ticketNumber: Long,
        val state: TicketPresenceState,
        val lastSeenAt: Instant,
    )

    private companion object {
        val MIN_STALE_AFTER: Duration = Duration.ofSeconds(30)
        val MAX_STALE_AFTER: Duration = Duration.ofMinutes(5)
    }
}

@Service
internal class InMemoryCollaborationRealtimeGateway(
    private val presenceBus: PresenceBus,
) : CollaborationRealtimeGateway, CollaborationPresenceConnectionTerminator {
    private val connections = mutableMapOf<String, RegisteredConnection>()

    @Synchronized
    override fun register(
        connection: PresenceConnection,
        sender: (CollaborationRealtimeMessage) -> Unit,
        closer: () -> Unit,
    ) {
        connections[connection.connectionId] = RegisteredConnection(connection, sender, closer)
    }

    @Synchronized
    override fun subscribe(connectionId: String, ticketNumber: Long) {
        connections[connectionId]?.tickets?.add(ticketNumber)
    }

    @Synchronized
    override fun unsubscribe(connectionId: String, ticketNumber: Long) {
        connections[connectionId]?.tickets?.remove(ticketNumber)
    }

    @Synchronized
    override fun unregister(connectionId: String) {
        connections.remove(connectionId)
    }

    override fun send(connectionId: String, message: CollaborationRealtimeMessage) {
        val sender = synchronized(this) { connections[connectionId]?.sender }
        runCatching { sender?.invoke(message) }
    }

    override fun broadcast(ticketNumber: Long, message: CollaborationRealtimeMessage) {
        val senders = synchronized(this) {
            connections.values.filter { ticketNumber in it.tickets }.map { it.sender }
        }
        senders.forEach { sender -> runCatching { sender(message) } }
    }

    override fun closeStaffConnections(staffId: UUID) {
        val toClose = synchronized(this) {
            connections.values.filter { it.connection.staffId == staffId }
        }
        toClose.forEach { registered ->
            presenceBus.disconnect(registered.connection.connectionId).forEach(::publish)
            unregister(registered.connection.connectionId)
            runCatching { registered.closer() }
        }
    }

    private fun publish(change: TicketPresenceChange) =
        broadcast(change.ticketNumber, CollaborationPresenceDeltaMessage(change))

    private data class RegisteredConnection(
        val connection: PresenceConnection,
        val sender: (CollaborationRealtimeMessage) -> Unit,
        val closer: () -> Unit,
        val tickets: MutableSet<Long> = mutableSetOf(),
    )
}

@Component
internal class TicketPresenceExpiryWorker(
    private val presenceBus: PresenceBus,
    private val gateway: CollaborationRealtimeGateway,
) {
    @Scheduled(fixedDelayString = "\${deskseed.collaboration.presence.expiry-cleanup-delay-ms:10000}")
    fun expireStalePresence() {
        presenceBus.expireStale().forEach { change ->
            gateway.broadcast(change.ticketNumber, CollaborationPresenceDeltaMessage(change))
        }
    }
}

@Component
internal class CollaborationTopologyGuard(
    @Value("\${deskseed.collaboration.presence.declared-instance-count:1}") private val declaredInstanceCount: Int,
    @Value("\${spring.profiles.active:}") activeProfiles: String,
) {
    private val production = activeProfiles.split(',').map(String::trim).any { it in setOf("prod", "production") }

    init {
        require(declaredInstanceCount > 0) { "declared instance count must be positive" }
        if (declaredInstanceCount > 1 && production) {
            error("in-memory collaboration presence cannot run with multiple declared production instances")
        }
        if (declaredInstanceCount > 1) {
            logger.warn("Collaboration presence is process-local; multi-instance dev/test semantics are incomplete")
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CollaborationTopologyGuard::class.java)
    }
}
