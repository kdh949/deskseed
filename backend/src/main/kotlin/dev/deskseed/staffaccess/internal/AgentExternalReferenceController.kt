package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.ExternalObjectType
import dev.deskseed.integration.ExternalReferenceView
import dev.deskseed.integration.ExternalSystemView
import dev.deskseed.ticketing.TicketCommandInvalidException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets/{ticketNumber}/external-references")
@Validated
internal class AgentExternalReferenceController(
    private val applicationService: AgentExternalReferenceApplicationService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<ExternalReferenceContextResponse> {
        val result = applicationService.list(principal, ticketNumber, interactionId, request.readContext())
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(result.ticketVersion.toString())
            .body(
                ExternalReferenceContextResponse(
                    ticketVersion = result.ticketVersion,
                    canManage = result.canManage,
                    availableSystems = result.availableSystems.map(ExternalSystemView::toResponse),
                    items = result.items.map(ExternalReferenceView::toResponse),
                ),
            )
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: CreateExternalReferenceRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ExternalReferenceCommandResponse> {
        requireMatchingVersion(ifMatch, body.expectedVersion)
        val result = applicationService.create(
            principal = principal,
            ticketNumber = ticketNumber,
            input = CreateTicketExternalReferenceInput(
                expectedVersion = body.expectedVersion,
                externalSystemId = body.externalSystemId,
                objectType = body.objectType,
                externalId = body.externalId,
                displayLabel = body.displayLabel,
                safeDeepLink = body.safeDeepLink,
                metadata = body.metadata,
                metadataObservedAt = body.metadataObservedAt,
            ),
            context = CommandContexts.from(request, RequestSource.AGENT_UI),
        )
        return ResponseEntity.created(
            URI.create("/api/v1/agent/tickets/$ticketNumber/external-references/${result.reference.id}"),
        )
            .cacheControl(CacheControl.noStore())
            .eTag(result.version.toString())
            .body(ExternalReferenceCommandResponse(result.version, result.reference.toResponse()))
    }

    @DeleteMapping("/{referenceId}")
    fun delete(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable referenceId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        request: HttpServletRequest,
    ): ResponseEntity<DeleteExternalReferenceResponse> {
        val expectedVersion = parseVersion(ifMatch)
        val result = applicationService.delete(
            principal = principal,
            ticketNumber = ticketNumber,
            referenceId = referenceId,
            expectedVersion = expectedVersion,
            context = CommandContexts.from(request, RequestSource.AGENT_UI),
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(result.version.toString())
            .body(DeleteExternalReferenceResponse(result.version, result.reference.id))
    }

    private fun HttpServletRequest.readContext() = AgentReadRequestContext(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        sessionId = getSession(false)?.id
            ?: throw AccessAuditUnavailableException(IllegalStateException("Authenticated staff session is unavailable")),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
    )

    private fun requireMatchingVersion(ifMatch: String, expectedVersion: Long) {
        if (parseVersion(ifMatch) != expectedVersion) {
            throw TicketCommandInvalidException("If-Match and expectedVersion must match")
        }
    }

    private fun parseVersion(ifMatch: String): Long = ifMatch.trim().removeSurrounding("\"").toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: throw TicketCommandInvalidException("If-Match must contain a non-negative ticket version")
}

internal data class CreateExternalReferenceRequest(
    val externalSystemId: UUID,
    val objectType: ExternalObjectType,
    @field:NotBlank @field:Size(max = 200)
    val externalId: String,
    @field:NotBlank @field:Size(max = 200)
    val displayLabel: String,
    @field:NotBlank @field:Size(max = 2048)
    val safeDeepLink: String,
    @field:Size(max = 8)
    val metadata: Map<String, Any>,
    val metadataObservedAt: Instant,
    @field:PositiveOrZero
    val expectedVersion: Long,
)

internal data class ExternalReferenceContextResponse(
    val ticketVersion: Long,
    val canManage: Boolean,
    val availableSystems: List<ExternalSystemResponse>,
    val items: List<ExternalReferenceResponse>,
)

internal data class ExternalReferenceCommandResponse(
    val ticketVersion: Long,
    val reference: ExternalReferenceResponse,
)

internal data class DeleteExternalReferenceResponse(
    val ticketVersion: Long,
    val removedReferenceId: UUID,
)

internal data class ExternalSystemResponse(
    val id: UUID,
    val systemKey: String,
    val displayName: String,
    val status: String,
    val allowedHostnames: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

internal data class ExternalReferenceResponse(
    val id: UUID,
    val system: ExternalSystemResponse,
    val objectType: String,
    val externalId: String,
    val displayLabel: String,
    val linkState: String,
    val safeDeepLink: String?,
    val metadata: Map<String, Any>,
    val metadataObservedAt: Instant,
    val createdBy: ExternalReferenceActorResponse,
    val createdAt: Instant,
)

internal data class ExternalReferenceActorResponse(
    val actorId: UUID,
    val displayName: String,
)

internal fun ExternalSystemView.toResponse() = ExternalSystemResponse(
    id = id,
    systemKey = systemKey,
    displayName = displayName,
    status = status.name,
    allowedHostnames = allowedHostnames,
    createdAt = createdAt,
    updatedAt = updatedAt,
    version = version,
)

private fun ExternalReferenceView.toResponse() = ExternalReferenceResponse(
    id = id,
    system = system.toResponse(),
    objectType = objectType.name,
    externalId = externalId,
    displayLabel = displayLabel,
    linkState = linkState.name,
    safeDeepLink = safeDeepLink,
    metadata = metadata,
    metadataObservedAt = metadataObservedAt,
    createdBy = ExternalReferenceActorResponse(createdBy.actorId, createdBy.displayName),
    createdAt = createdAt,
)
