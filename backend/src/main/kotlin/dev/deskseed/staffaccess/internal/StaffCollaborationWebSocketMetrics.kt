package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.CollaborationRealtimeErrorCode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicInteger

@Component
internal class StaffCollaborationWebSocketMetrics(meterRegistry: MeterRegistry) {
    private val activeConnections = AtomicInteger()
    private val connectionEvents = ConnectionOutcome.entries.associateWith { outcome ->
        Counter.builder("deskseed.collaboration.websocket.connection.events")
            .tag("outcome", outcome.tagValue)
            .register(meterRegistry)
    }
    private val messages = ClientMessageType.entries.associateWith { type ->
        Counter.builder("deskseed.collaboration.websocket.messages")
            .tag("type", type.tagValue)
            .register(meterRegistry)
    }
    private val rejections = CollaborationRealtimeErrorCode.entries.associateWith { code ->
        Counter.builder("deskseed.collaboration.websocket.rejections")
            .tag("code", code.name)
            .register(meterRegistry)
    }

    init {
        Gauge.builder("deskseed.collaboration.websocket.connections", activeConnections) { it.get().toDouble() }
            .description("Currently active staff collaboration WebSocket connections")
            .register(meterRegistry)
    }

    fun connectionAccepted() {
        activeConnections.incrementAndGet()
        connectionEvents.getValue(ConnectionOutcome.ACCEPTED).increment()
    }

    fun connectionRejected() {
        connectionEvents.getValue(ConnectionOutcome.REJECTED).increment()
    }

    fun connectionClosed() {
        activeConnections.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        connectionEvents.getValue(ConnectionOutcome.CLOSED).increment()
    }

    fun transportError() {
        connectionEvents.getValue(ConnectionOutcome.TRANSPORT_ERROR).increment()
    }

    fun message(type: ClientMessageType) {
        messages.getValue(type).increment()
    }

    fun rejected(code: CollaborationRealtimeErrorCode) {
        rejections.getValue(code).increment()
    }

    internal enum class ClientMessageType(val tagValue: String) {
        SUBSCRIBE("subscribe"),
        UNSUBSCRIBE("unsubscribe"),
        HEARTBEAT("heartbeat"),
        PRESENCE_STATE("presence-state"),
    }

    private enum class ConnectionOutcome(val tagValue: String) {
        ACCEPTED("accepted"),
        REJECTED("rejected"),
        CLOSED("closed"),
        TRANSPORT_ERROR("transport-error"),
    }
}
