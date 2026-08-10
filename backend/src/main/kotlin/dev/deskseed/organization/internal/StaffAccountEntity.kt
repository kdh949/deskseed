package dev.deskseed.organization.internal

import dev.deskseed.organization.StaffRole
import dev.deskseed.organization.StaffStatus
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
@Table(name = "staff_accounts")
internal class StaffAccountEntity(
    @Id
    val id: UUID,
    @Column(name = "email_normalized", nullable = false, unique = true, length = 254)
    val emailNormalized: String,
    @Column(name = "email_display", nullable = false, length = 254)
    val emailDisplay: String,
    @Column(name = "display_name", nullable = false, length = 100)
    var displayName: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val role: StaffRole,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: StaffStatus,
    @Column(name = "password_hash", nullable = false, length = 100)
    val passwordHash: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "last_login_at")
    var lastLoginAt: Instant? = null,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
