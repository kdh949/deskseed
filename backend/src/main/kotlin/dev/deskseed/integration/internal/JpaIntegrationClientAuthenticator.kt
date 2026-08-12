package dev.deskseed.integration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.AuthenticatedIntegrationClient
import dev.deskseed.integration.IntegrationAuthenticationRequest
import dev.deskseed.integration.IntegrationAuthenticationResult
import dev.deskseed.integration.IntegrationClientAuthenticator
import dev.deskseed.integration.IntegrationClientStatus
import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationScope
import dev.deskseed.integration.IntegrationTicketField
import dev.deskseed.integration.IntegrationTicketKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaIntegrationClientAuthenticator(
    private val credentialRepository: IntegrationCredentialRepository,
    private val clientRepository: IntegrationClientRepository,
    private val secretHasher: IntegrationSecretHasher,
    private val ipAllowlistPolicy: IpAllowlistPolicy,
    private val auditWriter: AdminSecurityAuditWriter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : IntegrationClientAuthenticator {
    @Transactional
    override fun authenticate(request: IntegrationAuthenticationRequest): IntegrationAuthenticationResult {
        require(RequestIdFilter.isValidIdentifier(request.requestId))
        require(RequestIdFilter.isValidIdentifier(request.correlationId))
        val parsed = KEY_PATTERN.matchEntire(request.apiKey)?.destructured
        val publicKeyId = parsed?.component1()
        val presentedSecret = parsed?.component2() ?: "invalid-integration-secret"
        // Lifecycle commands lock client then credential. Authentication uses the same order so
        // revoke/disable linearize before a result and cannot deadlock with last-used persistence.
        val locatedClientId = publicKeyId?.let(credentialRepository::findClientIdByPublicKeyId)
        val client = locatedClientId?.let(clientRepository::findLockedById)
        val credential = if (client == null) null else credentialRepository.findLockedByPublicKeyId(publicKeyId)
        val secretMatches = runCatching { secretHasher.matches(presentedSecret, credential?.secretHash) }.getOrDefault(false)
        val now = Instant.now(clock)
        val constraints = client?.let { decodeConstraints(it.resourceConstraintsJson) }
        val normalizedIp = ipAllowlistPolicy.normalize(request.remoteIp)
        val failureReason = when {
            parsed == null -> "MALFORMED_CREDENTIAL"
            credential == null || client == null -> "UNKNOWN_CREDENTIAL"
            !secretMatches -> "INVALID_SECRET"
            client.status != IntegrationClientStatus.ACTIVE.name -> "CLIENT_NOT_ACTIVE"
            credential.status == "REVOKED" -> "CREDENTIAL_REVOKED"
            !credential.expiresAt.isAfter(now) -> "CREDENTIAL_EXPIRED"
            credential.status == "RETIRING" && credential.overlapExpiresAt?.isAfter(now) != true -> "ROTATION_OVERLAP_EXPIRED"
            normalizedIp == null || !ipAllowlistPolicy.isAllowed(normalizedIp, constraints?.ipAllowlist) -> "IP_NOT_ALLOWED"
            else -> null
        }
        if (failureReason != null) {
            appendAuthenticationAudit(
                eventType = "INTEGRATION_AUTHENTICATION_FAILED",
                actorType = ActorType.SYSTEM,
                actorId = null,
                targetId = client?.id,
                request = request,
                metadata = mapOf(
                    "reason" to failureReason,
                    "publicKeyId" to (publicKeyId ?: "malformed"),
                    "remoteIp" to (normalizedIp ?: "invalid"),
                ),
                outcome = AdminSecurityOutcome.DENIED,
                occurredAt = now,
            )
            return IntegrationAuthenticationResult.Failure
        }
        checkNotNull(client)
        checkNotNull(credential)
        checkNotNull(constraints)
        checkNotNull(normalizedIp)
        credential.lastUsedAt = now
        credential.lastUsedIp = normalizedIp
        client.lastUsedAt = now
        client.lastUsedIp = normalizedIp
        client.updatedAt = now
        credentialRepository.save(credential)
        clientRepository.save(client)
        appendAuthenticationAudit(
            eventType = "INTEGRATION_CLIENT_LAST_USED",
            actorType = ActorType.INTEGRATION_CLIENT,
            actorId = client.id,
            targetId = client.id,
            request = request,
            metadata = mapOf(
                "credentialId" to credential.id.toString(),
                "publicKeyId" to credential.publicKeyId,
                "remoteIp" to normalizedIp,
            ),
            outcome = AdminSecurityOutcome.SUCCEEDED,
            occurredAt = now,
        )
        return IntegrationAuthenticationResult.Success(
            AuthenticatedIntegrationClient(
                id = client.id,
                name = client.name,
                scopes = decodeScopes(client.scopesJson),
                resourceConstraints = constraints,
                credentialId = credential.id,
            ),
        )
    }

    private fun appendAuthenticationAudit(
        eventType: String,
        actorType: ActorType,
        actorId: UUID?,
        targetId: UUID?,
        request: IntegrationAuthenticationRequest,
        metadata: Map<String, String>,
        outcome: AdminSecurityOutcome,
        occurredAt: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = actorType,
                actorId = actorId,
                actorDisplaySnapshot = null,
                source = RequestSource.PLATFORM_API,
                targetType = "INTEGRATION_CLIENT",
                targetId = targetId,
                outcome = outcome,
                requestId = request.requestId,
                correlationId = request.correlationId,
                metadata = metadata,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun decodeScopes(json: String): Set<IntegrationScope> = objectMapper.readValue(json, Array<String>::class.java)
        .map(IntegrationScope::fromValue)
        .toSet()

    private fun decodeConstraints(json: String): IntegrationResourceConstraints {
        val value = objectMapper.readValue(json, ConstraintsJson::class.java)
        return IntegrationResourceConstraints(
            allowedGroupIds = value.allowedGroupIds?.map(UUID::fromString)?.toSet(),
            allowedTicketKinds = value.allowedTicketKinds?.map(IntegrationTicketKind::valueOf)?.toSet(),
            allowedFields = value.allowedFields?.map { field ->
                IntegrationTicketField.entries.first { it.value == field }
            }?.toSet(),
            ipAllowlist = value.ipAllowlist?.toSet(),
        )
    }

    private data class ConstraintsJson(
        val allowedGroupIds: List<String>? = null,
        val allowedTicketKinds: List<String>? = null,
        val allowedFields: List<String>? = null,
        val ipAllowlist: List<String>? = null,
    )

    companion object {
        private val KEY_PATTERN = Regex("^dsk_live_([A-Za-z0-9_-]{16,32})\\.([A-Za-z0-9_-]{43})$")
    }
}
