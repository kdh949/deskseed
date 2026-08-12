package dev.deskseed.integration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.integration.CreateIntegrationClientCommand
import dev.deskseed.integration.INTEGRATION_CLIENT_MANAGE_AUTHORITY
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientAdministration
import dev.deskseed.integration.IntegrationClientConflictException
import dev.deskseed.integration.IntegrationClientNotFoundException
import dev.deskseed.integration.IntegrationClientPage
import dev.deskseed.integration.IntegrationClientStatus
import dev.deskseed.integration.IntegrationClientView
import dev.deskseed.integration.IntegrationCredentialIssue
import dev.deskseed.integration.IntegrationCredentialStatus
import dev.deskseed.integration.IntegrationCredentialView
import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationScope
import dev.deskseed.integration.IntegrationTicketField
import dev.deskseed.integration.IntegrationTicketKind
import dev.deskseed.integration.RotateIntegrationCredentialCommand
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaIntegrationClientAdministration(
    private val clientRepository: IntegrationClientRepository,
    private val credentialRepository: IntegrationCredentialRepository,
    private val keyGenerator: IntegrationApiKeyGenerator,
    private val secretHasher: IntegrationSecretHasher,
    private val ipAllowlistPolicy: IpAllowlistPolicy,
    private val auditWriter: AdminSecurityAuditWriter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : IntegrationClientAdministration {
    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun list(page: Int, size: Int): IntegrationClientPage {
        val result = clientRepository.findAll(
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))),
        )
        val credentials = credentialRepository.findAllByClientIdIn(result.content.map { it.id })
            .groupBy { it.clientId }
        return IntegrationClientPage(
            items = result.content.map { toView(it, credentials[it.id].orEmpty()) },
            page = result.number,
            size = result.size,
            totalCount = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun get(clientId: UUID): IntegrationClientView = toView(
        clientRepository.findById(clientId).orElseThrow(::IntegrationClientNotFoundException),
        credentialRepository.findAllByClientIdOrderBySequenceDesc(clientId),
    )

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional
    override fun create(
        command: CreateIntegrationClientCommand,
        actor: IntegrationAdminActor,
    ): IntegrationCredentialIssue {
        val now = Instant.now(clock)
        validate(command, now)
        val generated = keyGenerator.generate()
        val client = clientRepository.saveAndFlush(
            IntegrationClientEntity(
                id = UUID.randomUUID(),
                name = command.name.trim(),
                description = command.description.trim(),
                status = IntegrationClientStatus.ACTIVE.name,
                scopesJson = encodeScopes(command.scopes),
                resourceConstraintsJson = encodeConstraints(command.resourceConstraints),
                createdByStaffId = actor.staffId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val credential = credentialRepository.saveAndFlush(
            IntegrationCredentialEntity(
                id = UUID.randomUUID(),
                clientId = client.id,
                sequence = 1,
                publicKeyId = generated.publicKeyId,
                secretHash = secretHasher.hash(generated.secret),
                status = StoredCredentialStatus.ACTIVE.name,
                expiresAt = command.expiresAt,
                createdByStaffId = actor.staffId,
                createdAt = now,
            ),
        )
        appendLifecycleAudit(
            eventType = "INTEGRATION_CLIENT_CREATED",
            client = client,
            actor = actor,
            metadata = lifecycleMetadata(client, credential),
            occurredAt = now,
        )
        val view = toView(client, listOf(credential))
        return IntegrationCredentialIssue(view, toCredentialView(credential, now), generated.apiKey)
    }

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional
    override fun disable(clientId: UUID, actor: IntegrationAdminActor): IntegrationClientView {
        val now = Instant.now(clock)
        val client = lockedClient(clientId)
        if (client.status != IntegrationClientStatus.ACTIVE.name) {
            throw IntegrationClientConflictException("INTEGRATION_CLIENT_NOT_ACTIVE")
        }
        client.status = IntegrationClientStatus.DISABLED.name
        client.updatedAt = now
        clientRepository.saveAndFlush(client)
        appendLifecycleAudit("INTEGRATION_CLIENT_DISABLED", client, actor, emptyMap(), now)
        return toView(client, credentialRepository.findAllByClientIdOrderBySequenceDesc(clientId))
    }

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional
    override fun revoke(clientId: UUID, actor: IntegrationAdminActor): IntegrationClientView {
        val now = Instant.now(clock)
        val client = lockedClient(clientId)
        if (client.status == IntegrationClientStatus.REVOKED.name) {
            return toView(client, credentialRepository.findAllByClientIdOrderBySequenceDesc(clientId))
        }
        client.status = IntegrationClientStatus.REVOKED.name
        client.updatedAt = now
        clientRepository.save(client)
        val credentials = credentialRepository.findAllByClientIdOrderBySequenceDesc(clientId)
        credentials.filter { it.status != StoredCredentialStatus.REVOKED.name }.forEach {
            it.status = StoredCredentialStatus.REVOKED.name
            it.overlapExpiresAt = null
            it.revokedAt = now
        }
        credentialRepository.saveAll(credentials)
        credentialRepository.flush()
        appendLifecycleAudit(
            "INTEGRATION_CLIENT_REVOKED",
            client,
            actor,
            mapOf("credentialCount" to credentials.size.toString()),
            now,
        )
        return toView(client, credentials)
    }

    @PreAuthorize("hasAuthority('$INTEGRATION_CLIENT_MANAGE_AUTHORITY')")
    @Transactional
    override fun rotate(
        clientId: UUID,
        command: RotateIntegrationCredentialCommand,
        actor: IntegrationAdminActor,
    ): IntegrationCredentialIssue {
        val now = Instant.now(clock)
        require(command.expiresAt.isAfter(now)) { "Credential expiry must be in the future" }
        require(command.overlapSeconds in 0..MAX_OVERLAP_SECONDS) { "Rotation overlap is outside the allowed range" }
        val client = lockedClient(clientId)
        if (client.status != IntegrationClientStatus.ACTIVE.name) {
            throw IntegrationClientConflictException("INTEGRATION_CLIENT_NOT_ACTIVE")
        }
        val credentials = credentialRepository.findAllByClientIdOrderBySequenceDesc(clientId)
        val active = credentials.singleOrNull { it.status == StoredCredentialStatus.ACTIVE.name }
            ?: throw IntegrationClientConflictException("ACTIVE_CREDENTIAL_NOT_FOUND")
        credentials.filter { it.status == StoredCredentialStatus.RETIRING.name }.forEach {
            it.status = StoredCredentialStatus.REVOKED.name
            it.overlapExpiresAt = null
            it.revokedAt = now
        }
        if (command.overlapSeconds == 0L || !active.expiresAt.isAfter(now)) {
            active.status = StoredCredentialStatus.REVOKED.name
            active.revokedAt = now
            active.overlapExpiresAt = null
        } else {
            active.status = StoredCredentialStatus.RETIRING.name
            active.overlapExpiresAt = minOf(active.expiresAt, now.plusSeconds(command.overlapSeconds))
        }
        credentialRepository.saveAll(credentials)
        credentialRepository.flush()
        val generated = keyGenerator.generate()
        val newCredential = credentialRepository.saveAndFlush(
            IntegrationCredentialEntity(
                id = UUID.randomUUID(),
                clientId = clientId,
                sequence = credentials.maxOf { it.sequence } + 1,
                publicKeyId = generated.publicKeyId,
                secretHash = secretHasher.hash(generated.secret),
                status = StoredCredentialStatus.ACTIVE.name,
                expiresAt = command.expiresAt,
                rotatedFromCredentialId = active.id,
                createdByStaffId = actor.staffId,
                createdAt = now,
            ),
        )
        client.updatedAt = now
        clientRepository.save(client)
        appendLifecycleAudit(
            "INTEGRATION_CLIENT_ROTATED",
            client,
            actor,
            lifecycleMetadata(client, newCredential) + mapOf(
                "rotatedFromCredentialId" to active.id.toString(),
                "overlapSeconds" to command.overlapSeconds.toString(),
            ),
            now,
        )
        val allCredentials = listOf(newCredential) + credentials
        val view = toView(client, allCredentials)
        return IntegrationCredentialIssue(view, toCredentialView(newCredential, now), generated.apiKey)
    }

    private fun validate(command: CreateIntegrationClientCommand, now: Instant) {
        require(command.name.trim().isNotEmpty() && command.name.trim().length <= 100)
        require(command.description.trim().length <= 500)
        require(command.scopes.isNotEmpty())
        require(command.expiresAt.isAfter(now))
        ipAllowlistPolicy.validate(command.resourceConstraints)
    }

    private fun lockedClient(clientId: UUID): IntegrationClientEntity =
        clientRepository.findLockedById(clientId) ?: throw IntegrationClientNotFoundException()

    private fun appendLifecycleAudit(
        eventType: String,
        client: IntegrationClientEntity,
        actor: IntegrationAdminActor,
        metadata: Map<String, String>,
        occurredAt: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "INTEGRATION_CLIENT",
                targetId = client.id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun lifecycleMetadata(
        client: IntegrationClientEntity,
        credential: IntegrationCredentialEntity,
    ): Map<String, String> = mapOf(
        "clientName" to client.name,
        "credentialId" to credential.id.toString(),
        "publicKeyId" to credential.publicKeyId,
        "expiresAt" to credential.expiresAt.toString(),
        "scopes" to decodeScopes(client.scopesJson).map(IntegrationScope::value).sorted().joinToString(","),
        "constraintSummary" to constraintSummary(decodeConstraints(client.resourceConstraintsJson)),
    )

    private fun constraintSummary(constraints: IntegrationResourceConstraints): String = listOf(
        "groups=${constraints.allowedGroupIds?.size ?: "all"}",
        "kinds=${constraints.allowedTicketKinds?.size ?: "all"}",
        "fields=${constraints.allowedFields?.size ?: "all"}",
        "networks=${constraints.ipAllowlist?.size ?: 0}",
    ).joinToString(";")

    private fun toView(
        client: IntegrationClientEntity,
        credentials: List<IntegrationCredentialEntity>,
    ): IntegrationClientView {
        val now = Instant.now(clock)
        val credentialViews = credentials.sortedByDescending { it.sequence }.map { toCredentialView(it, now) }
        return IntegrationClientView(
            id = client.id,
            name = client.name,
            description = client.description,
            status = IntegrationClientStatus.valueOf(client.status),
            scopes = decodeScopes(client.scopesJson),
            resourceConstraints = decodeConstraints(client.resourceConstraintsJson),
            credentials = credentialViews,
            expiresAt = credentialViews.filter { it.status != IntegrationCredentialStatus.REVOKED }
                .maxOfOrNull { it.expiresAt },
            lastUsedAt = client.lastUsedAt,
            lastUsedIp = client.lastUsedIp,
            createdAt = client.createdAt,
        )
    }

    private fun toCredentialView(entity: IntegrationCredentialEntity, now: Instant): IntegrationCredentialView {
        val stored = StoredCredentialStatus.valueOf(entity.status)
        val effective = when {
            stored == StoredCredentialStatus.REVOKED -> IntegrationCredentialStatus.REVOKED
            !entity.expiresAt.isAfter(now) -> IntegrationCredentialStatus.EXPIRED
            stored == StoredCredentialStatus.RETIRING && entity.overlapExpiresAt?.isAfter(now) != true ->
                IntegrationCredentialStatus.EXPIRED
            stored == StoredCredentialStatus.RETIRING -> IntegrationCredentialStatus.RETIRING
            else -> IntegrationCredentialStatus.ACTIVE
        }
        return IntegrationCredentialView(
            id = entity.id,
            sequence = entity.sequence,
            publicKeyId = entity.publicKeyId,
            status = effective,
            expiresAt = entity.expiresAt,
            overlapExpiresAt = entity.overlapExpiresAt,
            createdAt = entity.createdAt,
            revokedAt = entity.revokedAt,
            lastUsedAt = entity.lastUsedAt,
            lastUsedIp = entity.lastUsedIp,
        )
    }

    private fun encodeScopes(scopes: Set<IntegrationScope>): String =
        objectMapper.writeValueAsString(scopes.map(IntegrationScope::value).sorted())

    private fun decodeScopes(json: String): Set<IntegrationScope> = objectMapper.readValue(json, Array<String>::class.java)
        .map(IntegrationScope::fromValue)
        .toSet()

    private fun encodeConstraints(constraints: IntegrationResourceConstraints): String = objectMapper.writeValueAsString(
        ConstraintsJson(
            allowedGroupIds = constraints.allowedGroupIds?.map(UUID::toString)?.sorted(),
            allowedTicketKinds = constraints.allowedTicketKinds?.map(Enum<*>::name)?.sorted(),
            allowedFields = constraints.allowedFields?.map(IntegrationTicketField::value)?.sorted(),
            ipAllowlist = constraints.ipAllowlist?.sorted(),
        ),
    )

    private fun decodeConstraints(json: String): IntegrationResourceConstraints {
        val value = objectMapper.readValue(json, ConstraintsJson::class.java)
        return IntegrationResourceConstraints(
            allowedGroupIds = value.allowedGroupIds?.map(UUID::fromString)?.toSet(),
            allowedTicketKinds = value.allowedTicketKinds?.map(IntegrationTicketKind::valueOf)?.toSet(),
            allowedFields = value.allowedFields?.map { field ->
                IntegrationTicketField.entries.firstOrNull { it.value == field }
                    ?: throw IllegalArgumentException("Unsupported integration ticket field")
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

    private enum class StoredCredentialStatus { ACTIVE, RETIRING, REVOKED }

    companion object {
        private const val MAX_OVERLAP_SECONDS = 86_400L
    }
}
