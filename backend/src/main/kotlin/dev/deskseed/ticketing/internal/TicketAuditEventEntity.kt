package dev.deskseed.ticketing.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ticket_audit_events")
internal class TicketAuditEventEntity(
    @Id
    val id: UUID,

    @Column(name = "audit_id", nullable = false)
    val auditId: UUID,

    @Column(name = "event_order", nullable = false)
    val eventOrder: Int,

    @Column(name = "event_type", nullable = false, length = 60)
    val eventType: String,

    @Column(name = "field_name", length = 60)
    val fieldName: String? = null,

    @Column(name = "old_value_json", columnDefinition = "text")
    val oldValueJson: String? = null,

    @Column(name = "new_value_json", columnDefinition = "text")
    val newValueJson: String? = null,

    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    val metadataJson: String = "{}",

    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: Instant,
)
