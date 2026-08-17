package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.CollaborationPresenceDeltaMessage
import dev.deskseed.collaboration.CollaborationPresenceSnapshotMessage
import dev.deskseed.collaboration.CollaborationRealtimeErrorCode
import dev.deskseed.collaboration.CollaborationRealtimeErrorMessage
import dev.deskseed.collaboration.CollaborationRealtimeGateway
import dev.deskseed.collaboration.CollaborationRealtimeMessage
import dev.deskseed.collaboration.PresenceBus
import dev.deskseed.collaboration.PresenceConnection
import dev.deskseed.collaboration.TicketPresenceState
import dev.deskseed.organization.StaffIdentityService
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
internal class CollaborationWebSocketConfiguration(
    private val handler: StaffCollaborationWebSocketHandler,
    private val originInterceptor: StaffCollaborationOriginInterceptor,
    @Value("\${deskseed.collaboration.websocket.allowed-origins:http://localhost:5173}") allowedOrigins: String,
) : WebSocketConfigurer {
    private val origins = allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty)

    init {
        require(origins.isNotEmpty() && origins.none { it == "*" }) {
            "deskseed.collaboration.websocket.allowed-origins must be a non-wildcard allowlist"
        }
    }

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, WEBSOCKET_PATH)
            .addInterceptors(originInterceptor)
            .setAllowedOrigins(*origins.toTypedArray())
    }

    companion object {
        const val WEBSOCKET_PATH = "/ws/agent/collaboration"
    }
}

@Component
internal class StaffCollaborationOriginInterceptor(
    @Value("\${deskseed.collaboration.websocket.allowed-origins:http://localhost:5173}") allowedOrigins: String,
) : HandshakeInterceptor {
    private val origins = allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    override fun beforeHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        attributes: MutableMap<String, Any>,
    ): Boolean {
        if (request.headers.origin !in origins) {
            response.setStatusCode(HttpStatus.FORBIDDEN)
            return false
        }
        return true
    }

    override fun afterHandshake(
        request: ServerHttpRequest,
        response: ServerHttpResponse,
        wsHandler: WebSocketHandler,
        exception: Exception?,
    ) = Unit
}

@Component
internal class StaffCollaborationWebSocketHandler(
    private val authorizer: StaffCollaborationSocketAuthorizer,
    private val presenceBus: PresenceBus,
    private val gateway: CollaborationRealtimeGateway,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${deskseed.collaboration.websocket.max-message-bytes:4096}") private val maxMessageBytes: Int,
    @Value("\${deskseed.collaboration.websocket.max-messages-per-minute:120}") private val maxMessagesPerMinute: Int,
) : TextWebSocketHandler() {
    private val connections = ConcurrentHashMap<String, SocketConnection>()

    init {
        require(maxMessageBytes in 256..4096) { "WebSocket message limit must be between 256 and 4096 bytes" }
        require(maxMessagesPerMinute in 1..120) { "WebSocket message rate must be between 1 and 120 per minute" }
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val principal = (session.principal as? Authentication)?.principal as? StaffPrincipal
        if (principal == null || !authorizer.isActive(principal)) {
            session.close(CloseStatus.POLICY_VIOLATION)
            return
        }
        session.textMessageSizeLimit = maxMessageBytes
        val decorated = ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, maxMessageBytes)
        val connection = SocketConnection(principal, decorated)
        connections[session.id] = connection
        gateway.register(
            connection = PresenceConnection(session.id, principal.id, principal.displayName),
            sender = { message -> send(decorated, message) },
            closer = { closeQuietly(decorated, CloseStatus.POLICY_VIOLATION) },
        )
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        val connection = connections[session.id] ?: return
        if (message.payloadLength > maxMessageBytes) {
            reject(session.id, CollaborationRealtimeErrorCode.MESSAGE_TOO_LARGE, false, close = true)
            return
        }
        if (!connection.accepts(Instant.now(clock), maxMessagesPerMinute)) {
            reject(session.id, CollaborationRealtimeErrorCode.RATE_LIMITED, true, retryAfterMillis = 60_000, close = true)
            return
        }
        val command = parse(message.payload)
        if (command == null) {
            reject(session.id, CollaborationRealtimeErrorCode.INVALID_MESSAGE, false)
            return
        }
        if (!authorizer.isActive(connection.principal)) {
            reject(session.id, CollaborationRealtimeErrorCode.UNAUTHORIZED, false, close = true)
            return
        }
        when (command) {
            is CollaborationClientCommand.Subscribe -> subscribe(session.id, connection, command.ticketNumber)
            is CollaborationClientCommand.Unsubscribe -> unsubscribe(session.id, command.ticketNumber)
            CollaborationClientCommand.Heartbeat -> heartbeat(session.id, connection)
            is CollaborationClientCommand.PresenceState -> updateState(session.id, connection, command.ticketNumber, command.state)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        connections.remove(session.id)
        presenceBus.disconnect(session.id).forEach { change ->
            gateway.broadcast(change.ticketNumber, CollaborationPresenceDeltaMessage(change))
        }
        gateway.unregister(session.id)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        closeQuietly(session, CloseStatus.SERVER_ERROR)
    }

    private fun subscribe(connectionId: String, connection: SocketConnection, ticketNumber: Long) {
        if (!authorizer.canRead(connection.principal, ticketNumber)) {
            reject(connectionId, CollaborationRealtimeErrorCode.FORBIDDEN, false)
            return
        }
        if (ticketNumber in connection.tickets) {
            reject(connectionId, CollaborationRealtimeErrorCode.INVALID_MESSAGE, false)
            return
        }
        val subscription = presenceBus.subscribe(
            PresenceConnection(connectionId, connection.principal.id, connection.principal.displayName),
            ticketNumber,
        )
        connection.tickets += ticketNumber
        gateway.subscribe(connectionId, ticketNumber)
        gateway.send(connectionId, CollaborationPresenceSnapshotMessage(subscription.snapshot))
        subscription.change?.let { change ->
            gateway.broadcast(change.ticketNumber, CollaborationPresenceDeltaMessage(change))
        }
    }

    private fun unsubscribe(connectionId: String, ticketNumber: Long) {
        connections[connectionId]?.tickets?.remove(ticketNumber)
        gateway.unsubscribe(connectionId, ticketNumber)
        presenceBus.unsubscribe(connectionId, ticketNumber)?.let { change ->
            gateway.broadcast(change.ticketNumber, CollaborationPresenceDeltaMessage(change))
        }
    }

    private fun heartbeat(connectionId: String, connection: SocketConnection) {
        val revokedTickets = connection.tickets.filterNot { ticketNumber ->
            authorizer.canRead(connection.principal, ticketNumber)
        }
        revokedTickets.forEach { ticketNumber ->
            unsubscribe(connectionId, ticketNumber)
            reject(connectionId, CollaborationRealtimeErrorCode.FORBIDDEN, false)
        }
        presenceBus.heartbeat(connectionId)
    }

    private fun updateState(
        connectionId: String,
        connection: SocketConnection,
        ticketNumber: Long,
        state: TicketPresenceState,
    ) {
        if (ticketNumber !in connection.tickets || !authorizer.canRead(connection.principal, ticketNumber)) {
            reject(connectionId, CollaborationRealtimeErrorCode.FORBIDDEN, false)
            return
        }
        presenceBus.updateState(connectionId, ticketNumber, state)?.let { change ->
            gateway.broadcast(change.ticketNumber, CollaborationPresenceDeltaMessage(change))
        }
    }

    private fun parse(raw: String): CollaborationClientCommand? = runCatching {
        val node = objectMapper.readTree(raw)
        if (!node.isObject || node.int("version") != 1) return@runCatching null
        when (node.text("type")) {
            "subscribe" -> positiveTicket(node)
                ?.takeIf { node.propertyNames() == setOf("version", "type", "ticketNumber") }
                ?.let(CollaborationClientCommand::Subscribe)
            "unsubscribe" -> positiveTicket(node)
                ?.takeIf { node.propertyNames() == setOf("version", "type", "ticketNumber") }
                ?.let(CollaborationClientCommand::Unsubscribe)
            "heartbeat" -> CollaborationClientCommand.Heartbeat.takeIf {
                node.propertyNames() == setOf("version", "type")
            }
            "presence.state" -> {
                if (node.propertyNames() != setOf("version", "type", "ticketNumber", "state")) {
                    return@runCatching null
                }
                val ticketNumber = positiveTicket(node) ?: return@runCatching null
                val state = node.text("state")?.let { TicketPresenceState.valueOf(it) } ?: return@runCatching null
                CollaborationClientCommand.PresenceState(ticketNumber, state)
            }
            else -> null
        }
    }.getOrNull()

    private fun positiveTicket(node: JsonNode): Long? =
        node.long("ticketNumber")?.takeIf { it > 0 }

    private fun JsonNode.text(field: String): String? =
        get(field)?.takeIf { it.isTextual }?.asString()

    private fun JsonNode.int(field: String): Int? =
        get(field)?.takeIf { it.isInt }?.intValue()

    private fun JsonNode.long(field: String): Long? =
        get(field)?.takeIf { it.canConvertToLong() }?.longValue()

    private fun reject(
        connectionId: String,
        code: CollaborationRealtimeErrorCode,
        retryable: Boolean,
        retryAfterMillis: Long? = null,
        close: Boolean = false,
    ) {
        gateway.send(connectionId, CollaborationRealtimeErrorMessage(code, retryable, retryAfterMillis))
        if (close) connections[connectionId]?.let { closeQuietly(it.session, CloseStatus.POLICY_VIOLATION) }
    }

    private fun send(session: WebSocketSession, message: CollaborationRealtimeMessage) {
        if (!session.isOpen) return
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(message.toWire())))
    }

    private fun CollaborationRealtimeMessage.toWire(): Map<String, Any?> = when (this) {
        is CollaborationPresenceSnapshotMessage -> mapOf(
            "version" to 1,
            "type" to "presence.snapshot",
            "ticketNumber" to snapshot.ticketNumber,
            "members" to snapshot.members.map { it.toWire() },
        )
        is CollaborationPresenceDeltaMessage -> mapOf(
            "version" to 1,
            "type" to "presence.delta",
            "ticketNumber" to change.ticketNumber,
            "action" to change.action.name,
            "member" to change.member.toWire(),
        )
        is dev.deskseed.collaboration.CollaborationTicketUpdatedMessage -> buildMap {
            put("version", 1)
            put("type", "ticket.updated")
            put("ticketNumber", update.ticketNumber)
            put("ticketVersion", update.ticketVersion)
            put("changedFields", update.changedFields.sorted())
            update.actorStaffId?.let { put("actorStaffId", it.toString()) }
            put("occurredAt", update.occurredAt.toString())
        }
        is CollaborationRealtimeErrorMessage -> buildMap {
            put("version", 1)
            put("type", "error")
            put("code", code.name)
            put("retryable", retryable)
            retryAfterMillis?.let { put("retryAfterMs", it) }
        }
    }

    private fun dev.deskseed.collaboration.TicketPresenceMember.toWire() = mapOf(
        "staffId" to staffId.toString(),
        "displayName" to displayName,
        "state" to state.name,
        "lastSeenAt" to lastSeenAt.toString(),
    )

    private fun closeQuietly(session: WebSocketSession, status: CloseStatus) {
        runCatching { if (session.isOpen) session.close(status) }
    }

    private data class SocketConnection(
        val principal: StaffPrincipal,
        val session: WebSocketSession,
        val tickets: MutableSet<Long> = ConcurrentHashMap.newKeySet(),
        val messages: ArrayDeque<Instant> = ArrayDeque(),
    ) {
        @Synchronized
        fun accepts(now: Instant, maxPerMinute: Int): Boolean {
            while (messages.firstOrNull()?.plus(RATE_WINDOW)?.isAfter(now) == false) messages.removeFirst()
            if (messages.size >= maxPerMinute) return false
            messages.addLast(now)
            return true
        }
    }

    private sealed interface CollaborationClientCommand {
        data class Subscribe(val ticketNumber: Long) : CollaborationClientCommand
        data class Unsubscribe(val ticketNumber: Long) : CollaborationClientCommand
        data object Heartbeat : CollaborationClientCommand
        data class PresenceState(val ticketNumber: Long, val state: TicketPresenceState) : CollaborationClientCommand
    }

    private companion object {
        const val SEND_TIME_LIMIT_MS = 5_000
        val RATE_WINDOW: Duration = Duration.ofMinutes(1)
    }
}

@Component
internal class StaffCollaborationSocketAuthorizer(
    private val ticketReadApplicationService: AgentTicketReadApplicationService,
    private val staffIdentityService: StaffIdentityService,
) {
    fun isActive(principal: StaffPrincipal): Boolean =
        staffIdentityService.findActiveById(principal.id)?.let { current ->
            current.role == principal.role && current.authorities == principal.authorities
        } ?: false

    fun canRead(principal: StaffPrincipal, ticketNumber: Long): Boolean {
        if (!isActive(principal)) return false
        return runCatching { ticketReadApplicationService.requireReadableTicket(principal, ticketNumber) }.isSuccess
    }
}
