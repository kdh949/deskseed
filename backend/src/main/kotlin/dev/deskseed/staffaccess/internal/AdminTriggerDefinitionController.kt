package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffRole
import dev.deskseed.trigger.TriggerActionDefinition
import dev.deskseed.trigger.TriggerActionType
import dev.deskseed.trigger.TriggerConditionDefinition
import dev.deskseed.trigger.TriggerConditionField
import dev.deskseed.trigger.TriggerConditionGroup
import dev.deskseed.trigger.TriggerConditionOperator
import dev.deskseed.trigger.TriggerDefinitionActor
import dev.deskseed.trigger.TriggerDefinitionAdministration
import dev.deskseed.trigger.TriggerDefinitionDraft
import dev.deskseed.trigger.TriggerDefinitionView
import dev.deskseed.trigger.TriggerEventType
import dev.deskseed.trigger.TriggerSetGroupAction
import dev.deskseed.trigger.TriggerValidationException
import dev.deskseed.trigger.TriggerWebhookAction
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
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
@RequestMapping("/api/v1/admin/triggers")
@Validated
internal class AdminTriggerDefinitionController(
    private val administration: TriggerDefinitionAdministration,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal principal: StaffPrincipal, request: HttpServletRequest) =
        administration.list(request.triggerActor(principal))

    @PostMapping
    fun create(
        @Valid @RequestBody body: TriggerDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TriggerDefinitionView> {
        val created = administration.create(body.position, body.toDraft(), request.triggerActor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/triggers/${created.id}"))
            .eTag(created.aggregateVersion.toString()).body(created)
    }

    @PostMapping("/{triggerId}/versions")
    fun createVersion(
        @PathVariable triggerId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: TriggerVersionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = triggerResponse(administration.createVersion(
        triggerId, triggerEtag(ifMatch), body.toDraft(), request.triggerActor(principal),
    ))

    @PutMapping("/{triggerId}/activation")
    fun activate(
        @PathVariable triggerId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: TriggerActivationRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = triggerResponse(administration.activate(
        triggerId, body.version, triggerEtag(ifMatch), request.triggerActor(principal),
    ))

    @DeleteMapping("/{triggerId}/activation")
    fun deactivate(
        @PathVariable triggerId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = triggerResponse(administration.deactivate(
        triggerId, triggerEtag(ifMatch), request.triggerActor(principal),
    ))

    @PutMapping("/{triggerId}/position")
    fun reposition(
        @PathVariable triggerId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: TriggerPositionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = triggerResponse(administration.reposition(
        triggerId, body.position, triggerEtag(ifMatch), request.triggerActor(principal),
    ))

    @PostMapping("/{triggerId}/versions/{version}/dry-run")
    fun dryRun(
        @PathVariable triggerId: UUID,
        @PathVariable version: Int,
        @Valid @RequestBody body: TriggerDryRunRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = administration.dryRun(
        triggerId, version, body.ticketNumber, body.eventType, request.triggerActor(principal),
    )
}

internal data class TriggerDefinitionRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Min(1) @field:Max(10_000) val position: Int,
    @field:NotEmpty @field:Size(max = 50) @field:Valid val conditions: List<TriggerConditionRequest>,
    @field:NotEmpty @field:Size(max = 50) @field:Valid val actions: List<TriggerActionRequest>,
) {
    fun toDraft() = TriggerDefinitionDraft(name, conditions.map(TriggerConditionRequest::toDefinition), actions.map(TriggerActionRequest::toDefinition))
}

internal data class TriggerVersionRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotEmpty @field:Size(max = 50) @field:Valid val conditions: List<TriggerConditionRequest>,
    @field:NotEmpty @field:Size(max = 50) @field:Valid val actions: List<TriggerActionRequest>,
) {
    fun toDraft() = TriggerDefinitionDraft(name, conditions.map(TriggerConditionRequest::toDefinition), actions.map(TriggerActionRequest::toDefinition))
}

internal data class TriggerConditionRequest(
    @field:NotNull val group: TriggerConditionGroup,
    @field:NotNull val field: TriggerConditionField,
    @field:NotNull val operator: TriggerConditionOperator,
    @field:Size(max = 120) val value: String? = null,
) {
    fun toDefinition() = TriggerConditionDefinition(group, field, operator, value)
}

internal data class TriggerActionRequest(
    @field:NotNull val type: TriggerActionType,
    val groupId: UUID? = null,
    @field:Size(max = 120) val eventType: String? = null,
) {
    fun toDefinition(): TriggerActionDefinition = when (type) {
        TriggerActionType.SET_GROUP -> TriggerSetGroupAction(groupId
            ?: throw TriggerValidationException("TRIGGER_ACTION_CONFIGURATION_INVALID", "groupId is required"))
        TriggerActionType.ENQUEUE_WEBHOOK -> TriggerWebhookAction(eventType ?: TriggerWebhookAction.WEBHOOK_EVENT_TYPE)
    }
}

internal data class TriggerActivationRequest(@field:Min(1) val version: Int)
internal data class TriggerPositionRequest(@field:Min(1) @field:Max(10_000) val position: Int)
internal data class TriggerDryRunRequest(@field:Min(1) val ticketNumber: Long, @field:NotNull val eventType: TriggerEventType)

private fun HttpServletRequest.triggerActor(principal: StaffPrincipal): TriggerDefinitionActor {
    val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
    return TriggerDefinitionActor(
        principal.id, principal.displayName, principal.role == StaffRole.ADMIN, principal.authorities,
        RequestSource.ADMIN_UI, context.requestId, context.correlationId,
    )
}

private fun triggerEtag(value: String): Long = Regex("\\\"(\\d+)\\\"").matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
    ?: throw TriggerValidationException("INVALID_ETAG", "If-Match must be a quoted decimal version")

private fun triggerResponse(view: TriggerDefinitionView): ResponseEntity<TriggerDefinitionView> =
    ResponseEntity.ok().eTag(view.aggregateVersion.toString()).body(view)
