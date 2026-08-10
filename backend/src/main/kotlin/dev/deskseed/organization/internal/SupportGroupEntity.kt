package dev.deskseed.organization.internal

import dev.deskseed.organization.OrganizationStatus
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
@Table(name = "support_groups")
internal class SupportGroupEntity(
    @Id
    val id: UUID,
    @Column(nullable = false, unique = true, length = 100)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OrganizationStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
