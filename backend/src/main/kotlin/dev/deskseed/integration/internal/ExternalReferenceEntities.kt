package dev.deskseed.integration.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "external_systems")
internal class ExternalSystemEntity(
    @Id
    val id: UUID,
    @Column(name = "system_key", nullable = false, length = 64)
    val systemKey: String,
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
    @Column(nullable = false, length = 20)
    var status: String,
    @Column(name = "allowed_hostnames_json", nullable = false, columnDefinition = "text")
    var allowedHostnamesJson: String,
    @Column(name = "created_by_staff_id", nullable = false)
    val createdByStaffId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "external_references")
internal class ExternalReferenceEntity(
    @Id
    val id: UUID,
    @Column(name = "ticket_id", nullable = false)
    val ticketId: UUID,
    @Column(name = "external_system_id", nullable = false)
    val externalSystemId: UUID,
    @Column(name = "object_type", nullable = false, length = 30)
    val objectType: String,
    @Column(name = "external_id", nullable = false, length = 200)
    val externalId: String,
    @Column(name = "display_label", nullable = false, length = 200)
    val displayLabel: String,
    @Column(name = "safe_deep_link", nullable = false, length = 2048)
    val safeDeepLink: String,
    @Column(name = "metadata_snapshot_json", nullable = false, columnDefinition = "text")
    val metadataSnapshotJson: String,
    @Column(name = "metadata_observed_at", nullable = false)
    val metadataObservedAt: Instant,
    @Column(name = "created_by_actor_type", nullable = false, length = 30)
    val createdByActorType: String,
    @Column(name = "created_by_actor_id", nullable = false)
    val createdByActorId: UUID,
    @Column(name = "created_by_actor_display", nullable = false, length = 100)
    val createdByActorDisplay: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)
