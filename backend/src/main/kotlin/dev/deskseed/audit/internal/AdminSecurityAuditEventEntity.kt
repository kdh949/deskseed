package dev.deskseed.audit.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "admin_security_audit_events")
internal class AdminSecurityAuditEventEntity(
    @Id
    val id: UUID,
    @Column(name = "event_type", nullable = false, length = 80)
    val eventType: String,
    @Column(name = "actor_type", nullable = false, length = 30)
    val actorType: String,
    @Column(name = "actor_id")
    val actorId: UUID?,
    @Column(name = "actor_display_snapshot", length = 100)
    val actorDisplaySnapshot: String?,
    @Column(nullable = false, length = 40)
    val source: String,
    @Column(name = "target_type", nullable = false, length = 60)
    val targetType: String,
    @Column(name = "target_id")
    val targetId: UUID?,
    @Column(nullable = false, length = 20)
    val outcome: String,
    @Column(name = "request_id", nullable = false, length = 100)
    val requestId: String,
    @Column(name = "correlation_id", nullable = false, length = 100)
    val correlationId: String,
    @Column(name = "metadata_json", nullable = false, columnDefinition = "text")
    val metadataJson: String,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
)
