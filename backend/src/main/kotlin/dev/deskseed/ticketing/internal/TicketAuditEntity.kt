package dev.deskseed.ticketing.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ticket_audits")
internal class TicketAuditEntity(
    @Id
    val id: UUID,

    @Column(name = "ticket_id", nullable = false)
    val ticketId: UUID,

    @Column(name = "ticket_version", nullable = false)
    val ticketVersion: Long,

    @Column(name = "actor_type", nullable = false, length = 30)
    val actorType: String,

    @Column(name = "actor_id")
    val actorId: UUID?,

    @Column(name = "source", nullable = false, length = 40)
    val source: String,

    @Column(name = "request_id", nullable = false, length = 100)
    val requestId: String,

    @Column(name = "correlation_id", nullable = false, length = 100)
    val correlationId: String,

    @Column(name = "command_id", nullable = false, length = 100)
    val commandId: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
)
