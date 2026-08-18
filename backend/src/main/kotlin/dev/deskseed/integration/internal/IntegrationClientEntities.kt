package dev.deskseed.integration.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "integration_clients")
internal class IntegrationClientEntity(
    @Id
    val id: UUID,
    @Column(nullable = false, length = 100)
    var name: String,
    @Column(nullable = false, length = 500)
    var description: String,
    @Column(nullable = false, length = 20)
    var status: String,
    @Column(name = "scopes_json", nullable = false, columnDefinition = "text")
    var scopesJson: String,
    @Column(name = "resource_constraints_json", nullable = false, columnDefinition = "text")
    var resourceConstraintsJson: String,
    @Column(name = "created_by_staff_id", nullable = false)
    val createdByStaffId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
    @Column(name = "last_used_ip", length = 64)
    var lastUsedIp: String? = null,
    @Column(name = "rate_limit_per_minute", nullable = false)
    var rateLimitPerMinute: Int = 60,
    @Column(name = "usage_count", nullable = false)
    var usageCount: Long = 0,
    @Column(name = "rate_policy_version", nullable = false)
    var ratePolicyVersion: Long = 0,
    @Version
    var version: Long = 0,
)

@Entity
@Table(name = "integration_credentials")
internal class IntegrationCredentialEntity(
    @Id
    val id: UUID,
    @Column(name = "client_id", nullable = false)
    val clientId: UUID,
    @Column(nullable = false)
    val sequence: Int,
    @Column(name = "public_key_id", nullable = false, length = 32)
    val publicKeyId: String,
    @Column(name = "secret_hash", nullable = false, length = 512)
    val secretHash: String,
    @Column(nullable = false, length = 20)
    var status: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "overlap_expires_at")
    var overlapExpiresAt: Instant? = null,
    @Column(name = "rotated_from_credential_id")
    val rotatedFromCredentialId: UUID? = null,
    @Column(name = "created_by_staff_id", nullable = false)
    val createdByStaffId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null,
    @Column(name = "last_used_ip", length = 64)
    var lastUsedIp: String? = null,
    @Version
    var version: Long = 0,
)
