package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.CreateExternalSystemCommand
import dev.deskseed.integration.ExternalSystemAdministration
import dev.deskseed.integration.ExternalSystemStatus
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.UpdateExternalSystemCommand
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/external-systems")
@Validated
internal class AdminExternalSystemController(
    private val administration: ExternalSystemAdministration,
) {
    @GetMapping
    fun list(): ResponseEntity<List<ExternalSystemResponse>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(administration.list().map { it.toResponse() })

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: CreateExternalSystemRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ExternalSystemResponse> {
        val result = administration.create(
            CreateExternalSystemCommand(body.systemKey, body.displayName, body.allowedHostnames),
            request.actor(principal),
        )
        return ResponseEntity.created(URI.create("/api/v1/admin/external-systems/${result.id}"))
            .cacheControl(CacheControl.noStore())
            .eTag(result.version.toString())
            .body(result.toResponse())
    }

    @PutMapping("/{systemId}")
    fun update(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable systemId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpdateExternalSystemRequest,
        request: HttpServletRequest,
    ): ResponseEntity<ExternalSystemResponse> {
        require(parseVersion(ifMatch) == body.expectedVersion) { "If-Match and expectedVersion must match" }
        val result = administration.update(
            systemId,
            UpdateExternalSystemCommand(
                displayName = body.displayName,
                status = body.status,
                allowedHostnames = body.allowedHostnames,
                expectedVersion = body.expectedVersion,
            ),
            request.actor(principal),
        )
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(result.version.toString())
            .body(result.toResponse())
    }

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

    private fun parseVersion(ifMatch: String): Long = ifMatch.trim().removeSurrounding("\"").toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("If-Match must contain a non-negative external-system version")
}

internal data class CreateExternalSystemRequest(
    @field:NotBlank @field:Size(max = 64)
    val systemKey: String,
    @field:NotBlank @field:Size(max = 100)
    val displayName: String,
    @field:Size(min = 1, max = 20)
    val allowedHostnames: Set<@Size(min = 1, max = 253) String>,
)

internal data class UpdateExternalSystemRequest(
    @field:NotBlank @field:Size(max = 100)
    val displayName: String,
    val status: ExternalSystemStatus,
    @field:Size(min = 1, max = 20)
    val allowedHostnames: Set<@Size(min = 1, max = 253) String>,
    @field:PositiveOrZero
    val expectedVersion: Long,
)
