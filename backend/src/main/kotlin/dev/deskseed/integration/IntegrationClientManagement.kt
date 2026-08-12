package dev.deskseed.integration

import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffAuthorityCatalog
import java.time.Instant
import java.util.UUID

const val INTEGRATION_CLIENT_MANAGE_AUTHORITY = StaffAuthorityCatalog.INTEGRATION_CLIENT_MANAGE

enum class IntegrationScope(val value: String) {
    TICKETS_CREATE("tickets:create"),
    TICKETS_READ("tickets:read"),
    TICKETS_UPDATE("tickets:update"),
    TICKETS_COMMENT_INTERNAL("tickets:comment:internal");

    companion object {
        fun fromValue(value: String): IntegrationScope = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("Unsupported integration scope")
    }
}

enum class IntegrationClientStatus { ACTIVE, DISABLED, REVOKED }

enum class IntegrationCredentialStatus { ACTIVE, RETIRING, EXPIRED, REVOKED }

enum class IntegrationTicketKind { CUSTOMER_REQUEST, INTERNAL_TASK }

enum class IntegrationTicketField(val value: String) {
    STATUS("status"),
    PRIORITY("priority"),
    GROUP_ID("groupId"),
    ASSIGNEE_ID("assigneeId");
}

data class IntegrationResourceConstraints(
    val allowedGroupIds: Set<UUID>? = null,
    val allowedTicketKinds: Set<IntegrationTicketKind>? = null,
    val allowedFields: Set<IntegrationTicketField>? = null,
    val ipAllowlist: Set<String>? = null,
)

data class IntegrationCredentialView(
    val id: UUID,
    val sequence: Int,
    val publicKeyId: String,
    val status: IntegrationCredentialStatus,
    val expiresAt: Instant,
    val overlapExpiresAt: Instant?,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
    val lastUsedIp: String?,
)

data class IntegrationClientView(
    val id: UUID,
    val name: String,
    val description: String,
    val status: IntegrationClientStatus,
    val scopes: Set<IntegrationScope>,
    val resourceConstraints: IntegrationResourceConstraints,
    val credentials: List<IntegrationCredentialView>,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val lastUsedIp: String?,
    val createdAt: Instant,
)

data class IntegrationClientPage(
    val items: List<IntegrationClientView>,
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val totalPages: Int,
)

data class CreateIntegrationClientCommand(
    val name: String,
    val description: String,
    val scopes: Set<IntegrationScope>,
    val resourceConstraints: IntegrationResourceConstraints,
    val expiresAt: Instant,
)

data class RotateIntegrationCredentialCommand(
    val expiresAt: Instant,
    val overlapSeconds: Long,
)

data class IntegrationCredentialIssue(
    val client: IntegrationClientView,
    val credential: IntegrationCredentialView,
    val apiKey: String,
)

data class IntegrationAdminActor(
    val staffId: UUID,
    val displayName: String,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
) {
    init {
        require(source == RequestSource.ADMIN_UI)
    }
}

interface IntegrationClientAdministration {
    fun list(page: Int = 0, size: Int = 50): IntegrationClientPage
    fun get(clientId: UUID): IntegrationClientView
    fun create(command: CreateIntegrationClientCommand, actor: IntegrationAdminActor): IntegrationCredentialIssue
    fun disable(clientId: UUID, actor: IntegrationAdminActor): IntegrationClientView
    fun revoke(clientId: UUID, actor: IntegrationAdminActor): IntegrationClientView
    fun rotate(
        clientId: UUID,
        command: RotateIntegrationCredentialCommand,
        actor: IntegrationAdminActor,
    ): IntegrationCredentialIssue
}

class IntegrationClientNotFoundException : RuntimeException()

class IntegrationClientConflictException(val code: String) : RuntimeException()
