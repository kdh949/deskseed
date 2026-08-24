package dev.deskseed.staffaccess.internal

import dev.deskseed.automation.AutomationActionType
import dev.deskseed.automation.AutomationDefinitionActor
import dev.deskseed.automation.AutomationDefinitionAdministration
import dev.deskseed.automation.AutomationDefinitionDraft
import dev.deskseed.automation.AutomationDefinitionView
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffRole
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
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
@RequestMapping("/api/v1/admin/automations")
@Validated
internal class AdminAutomationDefinitionController(
    private val administration: AutomationDefinitionAdministration,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal principal: StaffPrincipal, request: HttpServletRequest) =
        administration.list(request.automationActor(principal))

    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateAutomationRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<AutomationDefinitionView> {
        val created = administration.create(body.position, body.toDraft(), request.automationActor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/automations/${created.id}"))
            .eTag(created.aggregateVersion.toString()).body(created)
    }

    @PostMapping("/{automationId}/versions")
    fun version(
        @PathVariable automationId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: AutomationVersionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.createVersion(
        automationId, etag(ifMatch), body.toDraft(), request.automationActor(principal),
    ))

    @PutMapping("/{automationId}/activation")
    fun activate(
        @PathVariable automationId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: AutomationActivationRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.activate(
        automationId, body.version, etag(ifMatch), request.automationActor(principal),
    ))

    @DeleteMapping("/{automationId}/activation")
    fun deactivate(
        @PathVariable automationId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.deactivate(
        automationId, etag(ifMatch), request.automationActor(principal),
    ))

    @PostMapping("/{automationId}/versions/{version}/dry-run")
    fun dryRun(
        @PathVariable automationId: UUID,
        @PathVariable version: Int,
        @Valid @RequestBody body: AutomationDryRunRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = administration.dryRun(automationId, version, body.ticketNumber, request.automationActor(principal))
}

internal data class CreateAutomationRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Min(1) @field:Max(10_000) val position: Int,
    @field:Min(1) @field:Max(525_600) val solvedAgeMinutes: Int,
    @field:NotNull val actionType: AutomationActionType,
) { fun toDraft() = AutomationDefinitionDraft(name, solvedAgeMinutes, actionType) }

internal data class AutomationVersionRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Min(1) @field:Max(525_600) val solvedAgeMinutes: Int,
    @field:NotNull val actionType: AutomationActionType,
) { fun toDraft() = AutomationDefinitionDraft(name, solvedAgeMinutes, actionType) }

internal data class AutomationActivationRequest(@field:Min(1) val version: Int)
internal data class AutomationDryRunRequest(@field:Min(1) val ticketNumber: Long)

private fun HttpServletRequest.automationActor(principal: StaffPrincipal): AutomationDefinitionActor {
    val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
    return AutomationDefinitionActor(
        principal.id, principal.displayName, principal.role == StaffRole.ADMIN, principal.authorities,
        RequestSource.ADMIN_UI, context.requestId, context.correlationId,
    )
}

private fun etag(value: String): Long = Regex("\\\"(\\d+)\\\"").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
    ?: throw IllegalArgumentException("Automation If-Match is invalid")
private fun response(view: AutomationDefinitionView) = ResponseEntity.ok().eTag(view.aggregateVersion.toString()).body(view)
