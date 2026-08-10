package dev.deskseed.portal.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "request_access_tokens")
internal class RequestAccessTokenEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "ticket_id", nullable = false)
    val ticketId: UUID,

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
)
