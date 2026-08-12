package dev.deskseed.integration

import dev.deskseed.foundation.ActorType
import org.springframework.stereotype.Component
import java.util.UUID

data class IntegrationAuthenticationRequest(
    val apiKey: String,
    val remoteIp: String,
    val requestId: String,
    val correlationId: String,
)

data class AuthenticatedIntegrationClient(
    val id: UUID,
    val name: String,
    val actorType: ActorType = ActorType.INTEGRATION_CLIENT,
    val scopes: Set<IntegrationScope>,
    val resourceConstraints: IntegrationResourceConstraints,
    val credentialId: UUID,
)

sealed interface IntegrationAuthenticationResult {
    data class Success(val principal: AuthenticatedIntegrationClient) : IntegrationAuthenticationResult
    data object Failure : IntegrationAuthenticationResult
}

interface IntegrationClientAuthenticator {
    fun authenticate(request: IntegrationAuthenticationRequest): IntegrationAuthenticationResult
}

data class IntegrationResourceRequest(
    val groupId: UUID? = null,
    val ticketKind: IntegrationTicketKind? = null,
    val fields: Set<IntegrationTicketField> = emptySet(),
)

@Component
class IntegrationAuthorizationPolicy {
    fun isAllowed(
        principal: AuthenticatedIntegrationClient,
        requiredScope: IntegrationScope,
        resource: IntegrationResourceRequest,
    ): Boolean {
        if (requiredScope !in principal.scopes) return false
        val constraints = principal.resourceConstraints
        if (constraints.allowedGroupIds != null && resource.groupId !in constraints.allowedGroupIds) return false
        if (constraints.allowedTicketKinds != null && resource.ticketKind !in constraints.allowedTicketKinds) return false
        if (constraints.allowedFields != null && !constraints.allowedFields.containsAll(resource.fields)) return false
        return true
    }
}
