package dev.deskseed.integration

import dev.deskseed.organization.StaffAuthorityCatalog
import java.time.Instant
import java.util.UUID

const val EXTERNAL_SYSTEM_MANAGE_AUTHORITY = StaffAuthorityCatalog.EXTERNAL_SYSTEM_MANAGE

enum class ExternalSystemStatus { ACTIVE, DISABLED }

enum class ExternalObjectType { ORDER, PAYMENT, REFUND, USER, STORE, OPS_CASE, CUSTOM }

enum class ExternalReferenceLinkState { AVAILABLE, SYSTEM_DISABLED, HOST_NOT_ALLOWED }

data class ExternalSystemView(
    val id: UUID,
    val systemKey: String,
    val displayName: String,
    val status: ExternalSystemStatus,
    val allowedHostnames: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class CreateExternalSystemCommand(
    val systemKey: String,
    val displayName: String,
    val allowedHostnames: Set<String>,
)

data class UpdateExternalSystemCommand(
    val displayName: String,
    val status: ExternalSystemStatus,
    val allowedHostnames: Set<String>,
    val expectedVersion: Long,
)

interface ExternalSystemAdministration {
    fun list(): List<ExternalSystemView>
    fun create(command: CreateExternalSystemCommand, actor: IntegrationAdminActor): ExternalSystemView
    fun update(
        systemId: UUID,
        command: UpdateExternalSystemCommand,
        actor: IntegrationAdminActor,
    ): ExternalSystemView
}

data class ExternalReferenceActorView(
    val actorId: UUID,
    val displayName: String,
)

data class ExternalReferenceView(
    val id: UUID,
    val system: ExternalSystemView,
    val objectType: ExternalObjectType,
    val externalId: String,
    val displayLabel: String,
    val linkState: ExternalReferenceLinkState,
    val safeDeepLink: String?,
    val metadata: Map<String, Any>,
    val metadataObservedAt: Instant,
    val createdBy: ExternalReferenceActorView,
    val createdAt: Instant,
)

data class CreateExternalReference(
    val ticketId: UUID,
    val externalSystemId: UUID,
    val objectType: ExternalObjectType,
    val externalId: String,
    val displayLabel: String,
    val safeDeepLink: String,
    val metadata: Map<String, Any>,
    val metadataObservedAt: Instant,
    val actorId: UUID,
    val actorDisplayName: String,
)

data class ExternalReferenceMutation(
    val reference: ExternalReferenceView,
    val auditHostname: String,
    val auditMetadataKeys: Set<String>,
)

interface ExternalReferenceStore {
    fun listActiveSystems(): List<ExternalSystemView>
    fun listForTicket(ticketId: UUID): List<ExternalReferenceView>
    fun create(command: CreateExternalReference): ExternalReferenceMutation
    fun delete(ticketId: UUID, referenceId: UUID): ExternalReferenceMutation
}

class ExternalSystemNotFoundException : RuntimeException()

class ExternalSystemConflictException(val code: String, val currentVersion: Long? = null) : RuntimeException()

class ExternalReferenceNotFoundException : RuntimeException()

class ExternalReferenceConflictException(val code: String) : RuntimeException()

class ExternalReferenceValidationException(val code: String) : IllegalArgumentException(code)
