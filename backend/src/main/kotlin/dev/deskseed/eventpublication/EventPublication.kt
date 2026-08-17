package dev.deskseed.eventpublication

import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.UUID

/** Public-safe domain fact intended for at-least-once external delivery. */
data class DomainEventEnvelope(
    val id: UUID,
    val type: String,
    val version: Int,
    val occurredAt: Instant,
    val subject: String,
    val sequence: Long?,
    val correlationId: String,
    val causationId: String? = null,
    val actorType: ActorType,
    val actorId: UUID?,
    val source: RequestSource,
    val requestId: String,
    val commandId: String,
    val data: Map<String, String>,
) {
    init {
        require(EVENT_TYPE.matches(type)) { "Event type must be a stable dotted identifier" }
        require(version > 0) { "Event version must be positive" }
        require(SUBJECT.matches(subject)) { "Event subject must be a bounded stable identifier" }
        require(sequence == null || sequence >= 0) { "Event sequence must not be negative" }
        require(ID.matches(correlationId)) { "Event correlationId must be bounded" }
        require(causationId == null || ID.matches(causationId)) { "Event causationId must be bounded" }
        require(ID.matches(requestId)) { "Event requestId must be bounded" }
        require(ID.matches(commandId)) { "Event commandId must be bounded" }
        require(data.size <= 30) { "Event data must remain bounded" }
        data.forEach { (key, value) ->
            require(KEY.matches(key)) { "Event data key is invalid" }
            require(key.lowercase() !in SENSITIVE_DATA_KEYS) { "Event data key is not delivery-safe" }
            require(value.length <= 500 && value.none(Char::isISOControl)) { "Event data value is invalid" }
        }
    }

    private companion object {
        val EVENT_TYPE = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")
        val SUBJECT = Regex("[a-z][a-z0-9-]{0,31}:[A-Za-z0-9-]{1,100}")
        val ID = Regex("[A-Za-z0-9._:-]{1,100}")
        val KEY = Regex("[a-z][a-zA-Z0-9]{0,63}")
        val SENSITIVE_DATA_KEYS = setOf("body", "secret", "token", "authorization", "cookie", "password")
    }
}

enum class DomainEventVisibility {
    PUBLIC,
    INTERNAL,
}

data class DomainEventAppend(
    val envelope: DomainEventEnvelope,
    val visibility: DomainEventVisibility,
)

interface EventPublicationPort {
    /** Appends the delivery intent inside the caller's existing business transaction. */
    fun append(event: DomainEventAppend): UUID
}

enum class EventOutboxStatus {
    PENDING,
    LEASED,
    DELIVERED,
    DEAD_LETTER,
}

data class ClaimedDomainEvent(
    val envelope: DomainEventEnvelope,
    val visibility: DomainEventVisibility,
    val attemptCount: Int,
    val leaseOwner: String,
    val leaseExpiresAt: Instant,
)

interface EventOutboxOperations {
    fun claimNext(workerId: String, leaseDurationSeconds: Long): ClaimedDomainEvent?

    fun markDelivered(eventId: UUID, workerId: String)

    fun returnExpiredLeases(): Int
}
