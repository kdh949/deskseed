package dev.deskseed.organization.internal

import dev.deskseed.organization.GrantableAuditAuthority
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "staff_authority_grants")
internal class StaffAuthorityGrantEntity(
    @Id
    val id: UUID,
    @Column(name = "staff_id", nullable = false)
    val staffId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    val authority: GrantableAuditAuthority,
    @Column(name = "granted_by_staff_id", nullable = false)
    val grantedByStaffId: UUID,
    @Column(name = "granted_at", nullable = false)
    val grantedAt: Instant,
)
