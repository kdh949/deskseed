package dev.deskseed.organization.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

internal enum class GroupMembershipStatus {
    ACTIVE,
    INACTIVE,
}

@Entity
@Table(name = "group_memberships")
internal class GroupMembershipEntity(
    @Id
    val id: UUID,
    @Column(name = "group_id", nullable = false)
    val groupId: UUID,
    @Column(name = "staff_id", nullable = false)
    val staffId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: GroupMembershipStatus,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
