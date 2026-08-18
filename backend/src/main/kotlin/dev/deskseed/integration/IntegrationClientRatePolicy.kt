package dev.deskseed.integration

import java.time.Instant
import java.util.UUID

/**
 * The secret-free operational projection is deliberately separate from the existing client
 * credential view. It is safe for ADMIN configuration reads and does not become Platform API
 * authentication material.
 */
data class IntegrationClientRatePolicyView(
    val clientId: UUID,
    val rateLimitPerMinute: Int,
    val usageCount: Long,
    val lastUsedAt: Instant?,
    val version: Long,
    val updatedAt: Instant,
)

data class UpdateIntegrationClientRatePolicyCommand(
    val rateLimitPerMinute: Int,
    val expectedVersion: Long,
)

interface IntegrationClientRatePolicyAdministration {
    fun get(clientId: UUID): IntegrationClientRatePolicyView
    fun update(
        clientId: UUID,
        command: UpdateIntegrationClientRatePolicyCommand,
        actor: IntegrationAdminActor,
    ): IntegrationClientRatePolicyView
}

class IntegrationClientRatePolicyConflictException(val currentVersion: Long) : RuntimeException()
