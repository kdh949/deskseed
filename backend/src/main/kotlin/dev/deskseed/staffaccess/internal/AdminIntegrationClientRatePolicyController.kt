package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.integration.IntegrationAdminActor
import dev.deskseed.integration.IntegrationClientRatePolicyAdministration
import dev.deskseed.integration.IntegrationClientRatePolicyView
import dev.deskseed.integration.UpdateIntegrationClientRatePolicyCommand
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/integration-clients")
@Validated
internal class AdminIntegrationClientRatePolicyController(
    private val administration: IntegrationClientRatePolicyAdministration,
) {
    @GetMapping("/{clientId}/rate-policy")
    fun get(@PathVariable clientId: UUID): ResponseEntity<IntegrationClientRatePolicyResponse> {
        val policy = administration.get(clientId)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(policy.etag())
            .body(policy.toResponse())
    }

    @PatchMapping("/{clientId}/rate-policy")
    fun update(
        @PathVariable clientId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpdateIntegrationClientRatePolicyRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<IntegrationClientRatePolicyResponse> {
        val policy = administration.update(
            clientId,
            UpdateIntegrationClientRatePolicyCommand(body.rateLimitPerMinute, ifMatch.integrationClientVersion()),
            request.actor(principal),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(policy.etag()).body(policy.toResponse())
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal): IntegrationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return IntegrationAdminActor(principal.id, principal.displayName, context.source, context.requestId, context.correlationId)
    }
}

internal data class UpdateIntegrationClientRatePolicyRequest(
    @field:Min(1) @field:Max(10_000) val rateLimitPerMinute: Int,
)

internal data class IntegrationClientRatePolicyResponse(
    val clientId: UUID,
    val rateLimitPerMinute: Int,
    val usageCount: Long,
    val lastUsedAt: Instant?,
    val version: Long,
    val updatedAt: Instant,
)

private fun IntegrationClientRatePolicyView.toResponse() = IntegrationClientRatePolicyResponse(
    clientId, rateLimitPerMinute, usageCount, lastUsedAt, version, updatedAt,
)

private fun IntegrationClientRatePolicyView.etag() = "\"integration-client-v$version\""

private fun String.integrationClientVersion(): Long = Regex("^\\\"integration-client-v(\\d+)\\\"$")
    .matchEntire(this)
    ?.groupValues
    ?.get(1)
    ?.toLongOrNull()
    ?: throw IllegalArgumentException("Integration client rate policy If-Match is invalid")
