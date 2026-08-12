package dev.deskseed.ticketing.internal

import dev.deskseed.foundation.ActorType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

internal enum class TicketRelationType {
    PARENT_CHILD,
}

@Entity
@Table(name = "ticket_relations")
internal class TicketRelationEntity(
    @Id
    val id: UUID,

    @Column(name = "source_ticket_id", nullable = false)
    val sourceTicketId: UUID,

    @Column(name = "target_ticket_id", nullable = false)
    val targetTicketId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "relation_type", nullable = false, length = 40)
    val relationType: TicketRelationType,

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by_actor_type", nullable = false, length = 30)
    val createdByActorType: ActorType,

    @Column(name = "created_by_actor_id")
    val createdByActorId: UUID?,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
)
