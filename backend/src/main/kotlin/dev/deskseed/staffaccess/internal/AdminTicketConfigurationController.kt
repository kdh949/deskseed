package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketconfiguration.TicketConfigurationAdministration
import dev.deskseed.ticketconfiguration.TicketConfigurationAdminActor
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import dev.deskseed.ticketconfiguration.TicketCustomFieldType
import dev.deskseed.ticketconfiguration.TicketFieldDefinitionDraft
import dev.deskseed.ticketconfiguration.TicketFieldDefinitionView
import dev.deskseed.ticketconfiguration.TicketFieldOptionDraft
import dev.deskseed.ticketconfiguration.TicketFieldOptionUpdate
import dev.deskseed.ticketconfiguration.TicketFieldValidation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/ticket-fields")
@Validated
internal class AdminTicketConfigurationController(
    private val administration: TicketConfigurationAdministration,
) {
    @GetMapping
    fun list(@RequestParam(required = false) active: Boolean?) = administration.listFieldDefinitions(active)

    @PostMapping
    fun create(
        @Valid @RequestBody body: CreateTicketFieldDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFieldDefinitionView> {
        val created = administration.createFieldDefinition(body.toDraft(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/ticket-fields/${created.id}"))
            .eTag(created.version.toString())
            .body(created)
    }

    @GetMapping("/{fieldId}")
    fun get(@PathVariable fieldId: UUID): ResponseEntity<TicketFieldDefinitionView> =
        administration.getFieldDefinition(fieldId).let { ResponseEntity.ok().eTag(it.version.toString()).body(it) }

    @PutMapping("/{fieldId}")
    fun update(
        @PathVariable fieldId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpdateTicketFieldDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFieldDefinitionView> {
        val current = administration.getFieldDefinition(fieldId)
        val updated = administration.updateFieldDefinition(
            fieldId,
            parseEtag(ifMatch),
            body.merge(current),
            request.actor(principal),
        )
        return ResponseEntity.ok().eTag(updated.version.toString()).body(updated)
    }

    @PutMapping("/{fieldId}/activation")
    fun activation(
        @PathVariable fieldId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: ActivationRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<TicketFieldDefinitionView> {
        val updated = administration.setFieldDefinitionActivation(
            fieldId,
            parseEtag(ifMatch),
            body.active,
            request.actor(principal),
        )
        return ResponseEntity.ok().eTag(updated.version.toString()).body(updated)
    }

    @GetMapping("/{fieldId}/options")
    fun listOptions(@PathVariable fieldId: UUID) = administration.listFieldOptions(fieldId)

    @PostMapping("/{fieldId}/options")
    fun createOption(
        @PathVariable fieldId: UUID,
        @Valid @RequestBody body: CreateTicketFieldOptionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<dev.deskseed.ticketconfiguration.TicketFieldOptionView> {
        val created = administration.createFieldOption(fieldId, body.toDraft(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/ticket-fields/$fieldId/options/${created.id}"))
            .eTag(created.version.toString())
            .body(created)
    }

    @PutMapping("/{fieldId}/options/{optionId}")
    fun updateOption(
        @PathVariable fieldId: UUID,
        @PathVariable optionId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpdateTicketFieldOptionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<dev.deskseed.ticketconfiguration.TicketFieldOptionView> {
        val updated = administration.updateFieldOption(
            fieldId,
            optionId,
            parseEtag(ifMatch),
            body.toUpdate(),
            request.actor(principal),
        )
        return ResponseEntity.ok().eTag(updated.version.toString()).body(updated)
    }

    @PutMapping("/{fieldId}/options/order")
    fun reorderOptions(
        @PathVariable fieldId: UUID,
        @Valid @RequestBody body: ReorderIdsRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = administration.reorderFieldOptions(fieldId, body.ids, request.actor(principal))

    private fun HttpServletRequest.actor(principal: StaffPrincipal): TicketConfigurationAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return TicketConfigurationAdminActor(
            principal.id,
            principal.displayName,
            context.source,
            context.requestId,
            context.correlationId,
        )
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

internal data class CreateTicketFieldDefinitionRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$")
    @field:Size(max = 120) val machineKey: String,
    @field:NotNull val type: TicketCustomFieldType,
    @field:NotBlank @field:Size(max = 120) val staffLabel: String,
    @field:Size(max = 500) val staffDescription: String? = null,
    @field:Size(max = 120) val customerLabel: String? = null,
    @field:Size(max = 500) val customerDescription: String? = null,
    val customerVisible: Boolean = false,
    val customerEditable: Boolean = false,
    val agentVisible: Boolean = true,
    val agentEditable: Boolean = true,
    val searchable: Boolean = false,
    val analyticsEligible: Boolean = false,
    val sensitive: Boolean = false,
    @field:Valid val validation: TicketFieldValidation = TicketFieldValidation(),
) {
    fun toDraft() = TicketFieldDefinitionDraft(
        machineKey, type, staffLabel, staffDescription, customerLabel, customerDescription,
        customerVisible, customerEditable, agentVisible, agentEditable, searchable, analyticsEligible, sensitive, validation,
    )
}

internal data class UpdateTicketFieldDefinitionRequest(
    @field:Size(max = 120) val machineKey: String? = null,
    val type: TicketCustomFieldType? = null,
    @field:Size(max = 120) val staffLabel: String? = null,
    @field:Size(max = 500) val staffDescription: String? = null,
    @field:Size(max = 120) val customerLabel: String? = null,
    @field:Size(max = 500) val customerDescription: String? = null,
    val customerVisible: Boolean? = null,
    val customerEditable: Boolean? = null,
    val agentVisible: Boolean? = null,
    val agentEditable: Boolean? = null,
    val searchable: Boolean? = null,
    val analyticsEligible: Boolean? = null,
    val sensitive: Boolean? = null,
    @field:Valid val validation: TicketFieldValidation? = null,
) {
    fun merge(current: TicketFieldDefinitionView): TicketFieldDefinitionDraft = TicketFieldDefinitionDraft(
        machineKey = machineKey ?: current.machineKey,
        type = type ?: current.type,
        staffLabel = staffLabel ?: current.staffLabel,
        staffDescription = staffDescription ?: current.staffDescription,
        customerLabel = customerLabel ?: current.customerLabel,
        customerDescription = customerDescription ?: current.customerDescription,
        customerVisible = customerVisible ?: current.customerVisible,
        customerEditable = customerEditable ?: current.customerEditable,
        agentVisible = agentVisible ?: current.agentVisible,
        agentEditable = agentEditable ?: current.agentEditable,
        searchable = searchable ?: current.searchable,
        analyticsEligible = analyticsEligible ?: current.analyticsEligible,
        sensitive = sensitive ?: current.sensitive,
        validation = validation ?: current.validation,
    )
}

internal data class ActivationRequest(@field:NotNull val active: Boolean)

internal data class CreateTicketFieldOptionRequest(
    @field:NotBlank @field:Pattern(regexp = "^[a-z][a-z0-9-]*$") @field:Size(max = 80) val machineKey: String,
    @field:NotBlank @field:Size(max = 120) val staffLabel: String,
    @field:Size(max = 120) val customerLabel: String? = null,
    @field:NotNull val order: Int,
) {
    fun toDraft() = TicketFieldOptionDraft(machineKey, staffLabel, customerLabel, order)
}

internal data class UpdateTicketFieldOptionRequest(
    @field:NotBlank @field:Size(max = 120) val staffLabel: String,
    @field:Size(max = 120) val customerLabel: String? = null,
    @field:NotNull val active: Boolean,
) {
    fun toUpdate() = TicketFieldOptionUpdate(staffLabel, customerLabel, active)
}

internal data class ReorderIdsRequest(
    @field:NotEmpty @field:Size(max = 200) val ids: List<UUID>,
)
