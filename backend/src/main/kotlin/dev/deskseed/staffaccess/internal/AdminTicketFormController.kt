package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketconfiguration.TicketConfigurationAdminActor
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketFormActorKind
import dev.deskseed.ticketconfiguration.TicketFormActorPolicy
import dev.deskseed.ticketconfiguration.TicketFormAdministration
import dev.deskseed.ticketconfiguration.TicketFormConditionalRule
import dev.deskseed.ticketconfiguration.TicketFormDraft
import dev.deskseed.ticketconfiguration.TicketFormFieldBehavior
import dev.deskseed.ticketconfiguration.TicketFormFieldEffect
import dev.deskseed.ticketconfiguration.TicketFormFieldPlacement
import dev.deskseed.ticketconfiguration.TicketFormPreviewContext
import dev.deskseed.ticketconfiguration.TicketFormView
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
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
import tools.jackson.databind.JsonNode
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/ticket-forms")
@Validated
internal class AdminTicketFormController(
    private val administration: TicketFormAdministration,
) {
    @GetMapping
    fun list(): List<TicketFormView> = administration.listForms()

    @PostMapping
    fun create(
        @Valid @RequestBody body: UpsertTicketFormRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFormView> {
        val created = administration.createForm(body.toDraft(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/ticket-forms/${created.id}"))
            .eTag(created.version.toString()).body(created)
    }

    @GetMapping("/{formId}")
    fun get(@PathVariable formId: UUID): ResponseEntity<TicketFormView> = administration.getForm(formId)
        .let { ResponseEntity.ok().eTag(it.version.toString()).body(it) }

    @PutMapping("/{formId}")
    fun update(
        @PathVariable formId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpsertTicketFormRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFormView> {
        val updated = administration.updateForm(formId, parseEtag(ifMatch), body.toDraft(), request.actor(principal))
        return ResponseEntity.ok().eTag(updated.version.toString()).body(updated)
    }

    @PostMapping("/{formId}/publish")
    fun publish(
        @PathVariable formId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFormView> {
        val published = administration.publishForm(formId, parseEtag(ifMatch), request.actor(principal))
        return ResponseEntity.ok().eTag(published.version.toString()).body(published)
    }

    @PostMapping("/{formId}/archive")
    fun archive(
        @PathVariable formId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFormView> {
        val archived = administration.archiveForm(formId, parseEtag(ifMatch), request.actor(principal))
        return ResponseEntity.ok().eTag(archived.version.toString()).body(archived)
    }

    @PostMapping("/{formId}/preview")
    fun preview(
        @PathVariable formId: UUID,
        @Valid @RequestBody body: TicketFormPreviewRequest,
    ) = administration.previewForm(formId, body.toContext())

    @PostMapping("/validate")
    fun validate(@Valid @RequestBody body: UpsertTicketFormRequest) = administration.validateForm(body.toDraft())

    private fun HttpServletRequest.actor(principal: StaffPrincipal): TicketConfigurationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return TicketConfigurationAdminActor(principal.id, principal.displayName, context.source, context.requestId, context.correlationId)
    }

    private fun parseEtag(value: String): Long {
        val match = ETAG.matchEntire(value)
            ?: throw TicketConfigurationValidationException("INVALID_ETAG", "If-Match must be a quoted decimal version")
        return match.groupValues[1].toLongOrNull()
            ?: throw TicketConfigurationValidationException("INVALID_ETAG", "If-Match is invalid")
    }

    private companion object {
        val ETAG = Regex("\\\"(\\d+)\\\"")
    }
}

internal data class UpsertTicketFormRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:Size(max = 500) val description: String? = null,
    val defaultForCustomer: Boolean = false,
    val defaultForAgent: Boolean = false,
    @field:NotEmpty @field:Size(max = 100) @field:Valid val placements: List<TicketFormFieldPlacementRequest>,
    @field:Size(max = 100) @field:Valid val conditionalRules: List<TicketFormConditionalRuleRequest> = emptyList(),
    @field:Size(max = 50) val allowedCustomStatusIds: Set<UUID> = emptySet(),
) {
    fun toDraft() = TicketFormDraft(
        name, description, defaultForCustomer, defaultForAgent, placements.map { it.toPlacement() },
        conditionalRules.map { it.toRule() }, allowedCustomStatusIds,
    )
}

internal data class TicketFormFieldPlacementRequest(
    @field:NotNull val fieldId: UUID,
    @field:Min(0) val order: Int,
    @field:Valid @field:NotNull val customer: TicketFormActorPolicyRequest,
    @field:Valid @field:NotNull val agent: TicketFormActorPolicyRequest,
) {
    fun toPlacement() = TicketFormFieldPlacement(fieldId, order, customer.toPolicy(), agent.toPolicy())
}

internal data class TicketFormActorPolicyRequest(
    val visible: Boolean,
    val editable: Boolean,
    val required: Boolean,
) {
    fun toPolicy() = TicketFormActorPolicy(visible, editable, required)
}

internal data class TicketFormConditionalRuleRequest(
    @field:NotNull val id: UUID,
    @field:Min(0) val priority: Int,
    @field:NotNull val condition: JsonNode,
    @field:NotEmpty @field:Size(max = 20) val effects: List<TicketFormFieldEffectRequest>,
) {
    fun toRule() = TicketFormConditionalRule(id, priority, condition, effects.map { it.toEffect() })
}

internal data class TicketFormFieldEffectRequest(
    @field:NotNull val fieldId: UUID,
    @field:NotNull val behavior: TicketFormFieldBehavior,
) {
    fun toEffect() = TicketFormFieldEffect(fieldId, behavior)
}

internal data class TicketFormPreviewRequest(
    @field:NotNull val actorKind: TicketFormActorKind,
    @field:NotBlank val ticketKind: String,
    @field:NotBlank val statusCategory: String,
    val customStatusId: UUID? = null,
    val fieldValues: Map<String, JsonNode> = emptyMap(),
) {
    fun toContext(): TicketFormPreviewContext = TicketFormPreviewContext(
        actorKind,
        buildMap {
            put("ticketKind", ticketKind)
            put("statusCategory", statusCategory)
            customStatusId?.let { put("customStatusId", it.toString()) }
            fieldValues.forEach { (machineKey, value) -> atomic(value)?.let { put("field.$machineKey", it) } }
        },
    )

    private fun atomic(value: JsonNode): String? = listOf(
        "booleanValue", "numberValue", "optionId", "shortTextValue", "longTextValue",
    ).mapNotNull { property -> value.path(property).takeIf { !it.isMissingNode && !it.isNull }?.asText() }.singleOrNull()
}
