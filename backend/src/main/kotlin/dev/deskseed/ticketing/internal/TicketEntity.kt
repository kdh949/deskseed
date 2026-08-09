package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "tickets")
internal class TicketEntity(
    @Id
    val id: UUID,

    @Column(name = "ticket_number", nullable = false, unique = true)
    val ticketNumber: Long,

    @Column(name = "requester_id", nullable = false)
    val requesterId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 40)
    val kind: TicketKind,

    @Column(name = "subject", nullable = false, length = 200)
    val subject: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: TicketStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    var priority: TicketPriority,

    @Column(name = "group_id")
    var groupId: UUID? = null,

    @Column(name = "assignee_id")
    var assigneeId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 30)
    val channel: TicketChannel,

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,

    @Column(name = "solved_at")
    var solvedAt: Instant? = null,
)
