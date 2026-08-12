package dev.deskseed.integration.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.integration.CreateExternalReference
import dev.deskseed.integration.CreateExternalSystemCommand
import dev.deskseed.integration.EXTERNAL_SYSTEM_MANAGE_AUTHORITY
import dev.deskseed.integration.ExternalObjectType
import dev.deskseed.integration.ExternalReferenceActorView
import dev.deskseed.integration.ExternalReferenceConflictException
import dev.deskseed.integration.ExternalReferenceLinkState
import dev.deskseed.integration.ExternalReferenceMutation
import dev.deskseed.integration.ExternalReferenceNotFoundException
import dev.deskseed.integration.ExternalReferenceStore
import dev.deskseed.integration.ExternalReferenceView
import dev.deskseed.integration.ExternalSystemAdministration
import dev.deskseed.integration.ExternalSystemConflictException
import dev.deskseed.integration.ExternalSystemNotFoundException
import dev.deskseed.integration.ExternalSystemStatus
import dev.deskseed.integration.ExternalSystemView
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.UpdateExternalSystemCommand
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaExternalReferenceManagement(
    private val systemRepository: ExternalSystemRepository,
    private val referenceRepository: ExternalReferenceRepository,
    private val validation: ExternalReferenceValidation,
    private val auditWriter: AdminSecurityAuditWriter,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : ExternalSystemAdministration, ExternalReferenceStore {
    @PreAuthorize("hasAuthority('$EXTERNAL_SYSTEM_MANAGE_AUTHORITY')")
    @Transactional(readOnly = true)
    override fun list(): List<ExternalSystemView> =
        systemRepository.findTop100ByOrderByDisplayNameAscIdAsc().map(::toSystemView)

    @PreAuthorize("hasAuthority('$EXTERNAL_SYSTEM_MANAGE_AUTHORITY')")
    @Transactional
    override fun create(command: CreateExternalSystemCommand, actor: IntegrationAdminActor): ExternalSystemView {
        val now = Instant.now(clock)
        val systemKey = validation.normalizeSystemKey(command.systemKey)
        val displayName = validation.normalizeDisplayName(command.displayName)
        val hostnames = validation.normalizeHostnames(command.allowedHostnames)
        val entity = ExternalSystemEntity(
            id = UUID.randomUUID(),
            systemKey = systemKey,
            displayName = displayName,
            status = ExternalSystemStatus.ACTIVE.name,
            allowedHostnamesJson = objectMapper.writeValueAsString(hostnames),
            createdByStaffId = actor.staffId,
            createdAt = now,
            updatedAt = now,
        )
        try {
            systemRepository.saveAndFlush(entity)
        } catch (_: DataIntegrityViolationException) {
            throw ExternalSystemConflictException("EXTERNAL_SYSTEM_KEY_EXISTS")
        }
        appendSystemAudit("EXTERNAL_SYSTEM_CREATED", entity, actor, emptyMap(), now)
        return toSystemView(entity)
    }

    @PreAuthorize("hasAuthority('$EXTERNAL_SYSTEM_MANAGE_AUTHORITY')")
    @Transactional
    override fun update(
        systemId: UUID,
        command: UpdateExternalSystemCommand,
        actor: IntegrationAdminActor,
    ): ExternalSystemView {
        val now = Instant.now(clock)
        val entity = systemRepository.findLockedById(systemId) ?: throw ExternalSystemNotFoundException()
        if (entity.version != command.expectedVersion) {
            throw ExternalSystemConflictException("EXTERNAL_SYSTEM_VERSION_STALE", entity.version)
        }
        val previousStatus = entity.status
        val previousHostnames = decodeHostnames(entity.allowedHostnamesJson)
        entity.displayName = validation.normalizeDisplayName(command.displayName)
        entity.status = command.status.name
        entity.allowedHostnamesJson = objectMapper.writeValueAsString(validation.normalizeHostnames(command.allowedHostnames))
        entity.updatedAt = now
        systemRepository.saveAndFlush(entity)
        appendSystemAudit(
            "EXTERNAL_SYSTEM_UPDATED",
            entity,
            actor,
            mapOf(
                "statusFrom" to previousStatus,
                "statusTo" to entity.status,
                "hostCountFrom" to previousHostnames.size.toString(),
                "hostCountTo" to decodeHostnames(entity.allowedHostnamesJson).size.toString(),
            ),
            now,
        )
        return toSystemView(entity)
    }

    @Transactional(readOnly = true)
    override fun listActiveSystems(): List<ExternalSystemView> =
        systemRepository.findTop100ByStatusOrderByDisplayNameAscIdAsc(ExternalSystemStatus.ACTIVE.name)
            .map(::toSystemView)

    @Transactional(readOnly = true)
    override fun listForTicket(ticketId: UUID): List<ExternalReferenceView> {
        val references = referenceRepository.findTop100ByTicketIdOrderByCreatedAtDescIdDesc(ticketId)
        val systems = systemRepository.findAllById(references.map { it.externalSystemId }.distinct()).associateBy { it.id }
        return references.mapNotNull { reference -> systems[reference.externalSystemId]?.let { toReferenceView(reference, it) } }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: CreateExternalReference): ExternalReferenceMutation {
        val now = Instant.now(clock)
        val system = systemRepository.findLockedById(command.externalSystemId) ?: throw ExternalSystemNotFoundException()
        if (system.status != ExternalSystemStatus.ACTIVE.name) {
            throw ExternalReferenceConflictException("EXTERNAL_SYSTEM_INACTIVE")
        }
        val allowedHostnames = decodeHostnames(system.allowedHostnamesJson)
        val link = validation.validateLink(command.safeDeepLink, allowedHostnames)
        val metadata = validation.normalizeMetadata(command.metadata)
        val entity = ExternalReferenceEntity(
            id = UUID.randomUUID(),
            ticketId = command.ticketId,
            externalSystemId = system.id,
            objectType = command.objectType.name,
            externalId = validation.normalizeExternalId(command.externalId),
            displayLabel = validation.normalizeLabel(command.displayLabel),
            safeDeepLink = link.value,
            metadataSnapshotJson = objectMapper.writeValueAsString(metadata),
            metadataObservedAt = validation.validateObservedAt(command.metadataObservedAt, now),
            createdByActorType = "STAFF",
            createdByActorId = command.actorId,
            createdByActorDisplay = validation.normalizeActorDisplay(command.actorDisplayName),
            createdAt = now,
        )
        try {
            referenceRepository.saveAndFlush(entity)
        } catch (_: DataIntegrityViolationException) {
            throw ExternalReferenceConflictException("EXTERNAL_REFERENCE_EXISTS")
        }
        return ExternalReferenceMutation(toReferenceView(entity, system), link.hostname, metadata.keys)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun delete(ticketId: UUID, referenceId: UUID): ExternalReferenceMutation {
        val entity = referenceRepository.findLockedByTicketIdAndId(ticketId, referenceId)
            ?: throw ExternalReferenceNotFoundException()
        val system = systemRepository.findById(entity.externalSystemId).orElseThrow(::ExternalSystemNotFoundException)
        val metadata = decodeMetadata(entity.metadataSnapshotJson)
        val hostname = runCatching { java.net.URI(entity.safeDeepLink).host }.getOrNull().orEmpty()
        val mutation = ExternalReferenceMutation(toReferenceView(entity, system), hostname, metadata.keys)
        referenceRepository.delete(entity)
        referenceRepository.flush()
        return mutation
    }

    private fun appendSystemAudit(
        eventType: String,
        system: ExternalSystemEntity,
        actor: IntegrationAdminActor,
        changes: Map<String, String>,
        occurredAt: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = "EXTERNAL_SYSTEM",
                targetId = system.id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = mapOf(
                    "systemKey" to system.systemKey,
                    "status" to system.status,
                    "allowedHostCount" to decodeHostnames(system.allowedHostnamesJson).size.toString(),
                ) + changes,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun toSystemView(entity: ExternalSystemEntity) = ExternalSystemView(
        id = entity.id,
        systemKey = entity.systemKey,
        displayName = entity.displayName,
        status = ExternalSystemStatus.valueOf(entity.status),
        allowedHostnames = decodeHostnames(entity.allowedHostnamesJson),
        createdAt = entity.createdAt,
        updatedAt = entity.updatedAt,
        version = entity.version,
    )

    private fun toReferenceView(entity: ExternalReferenceEntity, system: ExternalSystemEntity): ExternalReferenceView {
        val metadata = decodeMetadata(entity.metadataSnapshotJson)
        val linkState = when {
            system.status != ExternalSystemStatus.ACTIVE.name -> ExternalReferenceLinkState.SYSTEM_DISABLED
            runCatching { validation.validateLink(entity.safeDeepLink, decodeHostnames(system.allowedHostnamesJson)) }
                .isSuccess -> ExternalReferenceLinkState.AVAILABLE
            else -> ExternalReferenceLinkState.HOST_NOT_ALLOWED
        }
        return ExternalReferenceView(
            id = entity.id,
            system = toSystemView(system),
            objectType = ExternalObjectType.valueOf(entity.objectType),
            externalId = entity.externalId,
            displayLabel = entity.displayLabel,
            linkState = linkState,
            safeDeepLink = entity.safeDeepLink.takeIf { linkState == ExternalReferenceLinkState.AVAILABLE },
            metadata = metadata,
            metadataObservedAt = entity.metadataObservedAt,
            createdBy = ExternalReferenceActorView(entity.createdByActorId, entity.createdByActorDisplay),
            createdAt = entity.createdAt,
        )
    }

    private fun decodeHostnames(json: String): List<String> =
        objectMapper.readValue(json, object : TypeReference<List<String>>() {})

    private fun decodeMetadata(json: String): Map<String, Any> =
        objectMapper.readValue(json, object : TypeReference<Map<String, Any>>() {})
}
