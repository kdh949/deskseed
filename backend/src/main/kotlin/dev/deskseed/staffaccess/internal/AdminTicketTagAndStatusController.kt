package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketconfiguration.CustomTicketStatusDraft
import dev.deskseed.ticketconfiguration.TicketConfigurationAdminActor
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketTagAndStatusAdministration
import dev.deskseed.ticketconfiguration.TicketTagDefinitionDraft
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
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
@RequestMapping("/api/v1/admin/ticket-tags")
@Validated
internal class AdminTicketTagController(
    private val administration: TicketTagAndStatusAdministration,
) {
    @GetMapping
    fun list() = administration.listTags()

    @PostMapping
    fun create(
        @Valid @RequestBody body: UpsertTicketTagDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<dev.deskseed.ticketconfiguration.TicketTagDefinitionView> {
        val created = administration.createTag(body.toDraft(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/ticket-tags/${created.id}"))
            .eTag(created.version.toString()).body(created)
    }

    @PutMapping("/{tagId}")
    fun update(
        @PathVariable tagId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpsertTicketTagDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<dev.deskseed.ticketconfiguration.TicketTagDefinitionView> {
        val updated = administration.updateTag(tagId, parseEtag(ifMatch), body.toDraft(), request.actor(principal))
        return ResponseEntity.ok().eTag(updated.version.toString()).body(updated)
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal): TicketConfigurationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return TicketConfigurationAdminActor(principal.id, principal.displayName, context.source, context.requestId, context.correlationId)
    }

    private fun parseEtag(value: String): Long = ETAG.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
        ?: throw TicketConfigurationValidationException("INVALID_ETAG", "If-Match must be a quoted decimal version")

    private companion object { val ETAG = Regex("\\\"(\\d+)\\\"") }
}

@RestController
@RequestMapping("/api/v1/admin/ticket-statuses")
@Validated
internal class AdminTicketStatusController(
    private val administration: TicketTagAndStatusAdministration,
) {
    @GetMapping
    fun list() = administration.listStatuses()

    @PostMapping
    fun create(
        @Valid @RequestBody body: UpsertCustomTicketStatusRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<dev.deskseed.ticketconfiguration.CustomTicketStatusView> {
        val created = administration.createStatus(body.toDraft(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/ticket-statuses/${created.id}"))
            .eTag(created.version.toString()).body(created)
    }

    @PutMapping("/{statusId}")
    fun update(
        @PathVariable statusId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpsertCustomTicketStatusRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<dev.deskseed.ticketconfiguration.CustomTicketStatusView> {
        val updated = administration.updateStatus(statusId, parseEtag(ifMatch), body.toDraft(), request.actor(principal))
        return ResponseEntity.ok().eTag(updated.version.toString()).body(updated)
    }

    @PutMapping("/order")
    fun reorder(
        @Valid @RequestBody body: ReorderTicketStatusIdsRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = administration.reorderStatuses(body.ids, request.actor(principal))

    private fun HttpServletRequest.actor(principal: StaffPrincipal): TicketConfigurationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return TicketConfigurationAdminActor(principal.id, principal.displayName, context.source, context.requestId, context.correlationId)
    }

    private fun parseEtag(value: String): Long = ETAG.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
        ?: throw TicketConfigurationValidationException("INVALID_ETAG", "If-Match must be a quoted decimal version")

    private companion object { val ETAG = Regex("\\\"(\\d+)\\\"") }
}

internal data class UpsertTicketTagDefinitionRequest(
    @field:NotBlank @field:Pattern(regexp = "^[A-Za-z0-9](?:[A-Za-z0-9_-]{0,78}[A-Za-z0-9])?$") @field:Size(max = 80)
    val value: String,
    @field:NotBlank @field:Size(max = 120) val label: String,
    val active: Boolean = true,
) {
    fun toDraft() = TicketTagDefinitionDraft(value, label, active)
}

internal data class UpsertCustomTicketStatusRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]*$") @field:Size(max = 80) val machineKey: String,
    @field:NotBlank @field:Size(max = 120) val agentLabel: String,
    @field:Size(max = 120) val customerLabel: String? = null,
    @field:NotNull val statusCategory: TicketStatus,
    val active: Boolean,
    @field:Min(0) val order: Int,
    val defaultForCategory: Boolean = false,
    @field:Size(max = 50) val allowedFormIds: Set<UUID> = emptySet(),
    @field:Size(max = 500) val description: String? = null,
) {
    fun toDraft() = CustomTicketStatusDraft(
        machineKey, agentLabel, customerLabel, statusCategory, active, order, defaultForCategory, allowedFormIds, description,
    )
}

internal data class ReorderTicketStatusIdsRequest(@field:NotEmpty @field:Size(max = 200) val ids: List<UUID>)
