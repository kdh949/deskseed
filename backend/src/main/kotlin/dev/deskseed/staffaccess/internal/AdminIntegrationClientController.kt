package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.CreateIntegrationClientCommand
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientAdministration
import dev.deskseed.integration.IntegrationClientPage
import dev.deskseed.integration.IntegrationClientView
import dev.deskseed.integration.IntegrationCredentialIssue
import dev.deskseed.integration.IntegrationCredentialView
import dev.deskseed.integration.IntegrationResourceConstraints
import dev.deskseed.integration.IntegrationScope
import dev.deskseed.integration.IntegrationTicketField
import dev.deskseed.integration.IntegrationTicketKind
import dev.deskseed.integration.RotateIntegrationCredentialCommand
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/integration-clients")
@Validated
internal class AdminIntegrationClientController(
    private val administration: IntegrationClientAdministration,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<List<IntegrationClientResponse>> = pageResponse(administration.list(page, size))

    @GetMapping("/{clientId}")
    fun get(@PathVariable clientId: UUID): ResponseEntity<IntegrationClientResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.get(clientId).toResponse())

    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateIntegrationClientRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<IntegrationCredentialIssueResponse> {
        val issue = administration.create(body.toCommand(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/integration-clients/${issue.client.id}"))
            .cacheControl(CacheControl.noStore())
            .body(issue.toResponse())
    }

    @PostMapping("/{clientId}/disable")
    fun disable(
        @PathVariable clientId: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<IntegrationClientResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.disable(clientId, request.actor(principal)).toResponse())

    @PostMapping("/{clientId}/revoke")
    fun revoke(
        @PathVariable clientId: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<IntegrationClientResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.revoke(clientId, request.actor(principal)).toResponse())

    @PostMapping("/{clientId}/rotate")
    fun rotate(
        @PathVariable clientId: UUID,
        @Valid @RequestBody body: RotateIntegrationCredentialRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<IntegrationCredentialIssueResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            administration.rotate(
                clientId,
                RotateIntegrationCredentialCommand(body.expiresAt, body.overlapSeconds),
                request.actor(principal),
            ).toResponse(),
        )

    private fun HttpServletRequest.actor(principal: StaffPrincipal): IntegrationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return IntegrationAdminActor(
            staffId = principal.id,
            displayName = principal.displayName,
            source = context.source,
            requestId = context.requestId,
            correlationId = context.correlationId,
        )
    }

    private fun pageResponse(page: IntegrationClientPage): ResponseEntity<List<IntegrationClientResponse>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Page-Number", page.page.toString())
            .header("X-Page-Size", page.size.toString())
            .header("X-Total-Count", page.totalCount.toString())
            .header("X-Total-Pages", page.totalPages.toString())
            .body(page.items.map(IntegrationClientView::toResponse))
}

internal data class CreateIntegrationClientRequest(
    @field:NotBlank @field:Size(max = 100)
    val name: String,
    @field:Size(max = 500)
    val description: String,
    @field:Size(min = 1, max = 4)
    val scopes: Set<String>,
    @field:Valid
    val resourceConstraints: IntegrationResourceConstraintsRequest,
    val expiresAt: Instant,
) {
    fun toCommand() = CreateIntegrationClientCommand(
        name = name,
        description = description,
        scopes = scopes.map(IntegrationScope::fromValue).toSet(),
        resourceConstraints = resourceConstraints.toModel(),
        expiresAt = expiresAt,
    )
}

internal data class RotateIntegrationCredentialRequest(
    val expiresAt: Instant,
    @field:Min(0) @field:Max(86_400)
    val overlapSeconds: Long,
)

internal data class IntegrationResourceConstraintsRequest(
    @field:Size(max = 100)
    val allowedGroupIds: Set<UUID>? = null,
    @field:Size(max = 2)
    val allowedTicketKinds: Set<IntegrationTicketKind>? = null,
    @field:Size(max = 4)
    val allowedFields: Set<String>? = null,
    @field:Size(max = 32)
    val ipAllowlist: Set<@Size(min = 2, max = 64) String>? = null,
) {
    fun toModel() = IntegrationResourceConstraints(
        allowedGroupIds = allowedGroupIds,
        allowedTicketKinds = allowedTicketKinds,
        allowedFields = allowedFields?.map { field ->
            IntegrationTicketField.entries.firstOrNull { it.value == field }
                ?: throw IllegalArgumentException("Unsupported integration ticket field")
        }?.toSet(),
        ipAllowlist = ipAllowlist,
    )
}

internal data class IntegrationClientResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val status: String,
    val scopes: List<String>,
    val resourceConstraints: IntegrationResourceConstraintsResponse,
    val credentials: List<IntegrationCredentialResponse>,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val lastUsedIp: String?,
    val createdAt: Instant,
)

internal data class IntegrationResourceConstraintsResponse(
    val allowedGroupIds: List<UUID>?,
    val allowedTicketKinds: List<String>?,
    val allowedFields: List<String>?,
    val ipAllowlist: List<String>?,
)

internal data class IntegrationCredentialResponse(
    val id: UUID,
    val sequence: Int,
    val publicKeyId: String,
    val status: String,
    val expiresAt: Instant,
    val overlapExpiresAt: Instant?,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val lastUsedAt: Instant?,
    val lastUsedIp: String?,
)

internal data class IntegrationCredentialIssueResponse(
    val client: IntegrationClientResponse,
    val credential: IntegrationCredentialResponse,
    val apiKey: String,
)

private fun IntegrationClientView.toResponse() = IntegrationClientResponse(
    id = id,
    name = name,
    description = description,
    status = status.name,
    scopes = scopes.map(IntegrationScope::value).sorted(),
    resourceConstraints = IntegrationResourceConstraintsResponse(
        allowedGroupIds = resourceConstraints.allowedGroupIds?.sortedBy(UUID::toString),
        allowedTicketKinds = resourceConstraints.allowedTicketKinds?.map(Enum<*>::name)?.sorted(),
        allowedFields = resourceConstraints.allowedFields?.map(IntegrationTicketField::value)?.sorted(),
        ipAllowlist = resourceConstraints.ipAllowlist?.sorted(),
    ),
    credentials = credentials.map(IntegrationCredentialView::toResponse),
    expiresAt = expiresAt,
    lastUsedAt = lastUsedAt,
    lastUsedIp = lastUsedIp,
    createdAt = createdAt,
)

private fun IntegrationCredentialView.toResponse() = IntegrationCredentialResponse(
    id = id,
    sequence = sequence,
    publicKeyId = publicKeyId,
    status = status.name,
    expiresAt = expiresAt,
    overlapExpiresAt = overlapExpiresAt,
    createdAt = createdAt,
    revokedAt = revokedAt,
    lastUsedAt = lastUsedAt,
    lastUsedIp = lastUsedIp,
)

private fun IntegrationCredentialIssue.toResponse() = IntegrationCredentialIssueResponse(
    client = client.toResponse(),
    credential = credential.toResponse(),
    apiKey = apiKey,
)
