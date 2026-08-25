package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.macro.MacroActionDefinition
import dev.deskseed.macro.MacroActionType
import dev.deskseed.macro.MacroAddTagAction
import dev.deskseed.macro.MacroAssigneeAction
import dev.deskseed.macro.MacroCommentAction
import dev.deskseed.macro.MacroCustomFieldAction
import dev.deskseed.macro.MacroCustomStatusAction
import dev.deskseed.macro.MacroDefinitionActor
import dev.deskseed.macro.MacroDefinitionAdministration
import dev.deskseed.macro.MacroDefinitionDraft
import dev.deskseed.macro.MacroDefinitionView
import dev.deskseed.macro.MacroGroupAction
import dev.deskseed.macro.MacroPriorityAction
import dev.deskseed.macro.MacroRemoveTagAction
import dev.deskseed.macro.MacroScope
import dev.deskseed.macro.MacroStatusAction
import dev.deskseed.macro.MacroValidationException
import dev.deskseed.organization.StaffRole
import dev.deskseed.ticketing.CommentVisibility
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
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
@RequestMapping("/api/v1/agent")
@Validated
internal class AgentMacroDefinitionController(
    private val administration: MacroDefinitionAdministration,
) {
    @GetMapping("/macros")
    fun listAccessible(@AuthenticationPrincipal principal: StaffPrincipal, request: HttpServletRequest) =
        administration.listAccessible(request.actor(principal, RequestSource.AGENT_UI))

    @GetMapping("/personal-macros")
    fun listManaged(@AuthenticationPrincipal principal: StaffPrincipal, request: HttpServletRequest) =
        administration.listManaged(MacroScope.PERSONAL, request.actor(principal, RequestSource.AGENT_UI))

    @PostMapping("/personal-macros")
    fun create(
        @Valid @RequestBody body: MacroDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<MacroDefinitionView> {
        val created = administration.create(MacroScope.PERSONAL, body.toDraft(), request.actor(principal, RequestSource.AGENT_UI))
        return ResponseEntity.created(URI.create("/api/v1/agent/personal-macros/${created.id}"))
            .eTag(created.aggregateVersion.toString())
            .body(created)
    }

    @PostMapping("/personal-macros/{macroId}/versions")
    fun createVersion(
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: MacroDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.createVersion(
        macroId,
        parseMacroEtag(ifMatch),
        body.toDraft(),
        request.actor(principal, RequestSource.AGENT_UI),
    ))

    @PutMapping("/personal-macros/{macroId}/activation")
    fun activate(
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: MacroActivationRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.activate(
        macroId,
        body.version,
        parseMacroEtag(ifMatch),
        request.actor(principal, RequestSource.AGENT_UI),
    ))

    @DeleteMapping("/personal-macros/{macroId}/activation")
    fun deactivate(
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.deactivate(
        macroId,
        parseMacroEtag(ifMatch),
        request.actor(principal, RequestSource.AGENT_UI),
    ))
}

@RestController
@RequestMapping("/api/v1/admin/shared-macros")
@Validated
internal class AdminSharedMacroDefinitionController(
    private val administration: MacroDefinitionAdministration,
) {
    @GetMapping
    fun list(@AuthenticationPrincipal principal: StaffPrincipal, request: HttpServletRequest) =
        administration.listManaged(MacroScope.SHARED, request.actor(principal, RequestSource.ADMIN_UI))

    @PostMapping
    fun create(
        @Valid @RequestBody body: MacroDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<MacroDefinitionView> {
        val created = administration.create(MacroScope.SHARED, body.toDraft(), request.actor(principal, RequestSource.ADMIN_UI))
        return ResponseEntity.created(URI.create("/api/v1/admin/shared-macros/${created.id}"))
            .eTag(created.aggregateVersion.toString())
            .body(created)
    }

    @PostMapping("/{macroId}/versions")
    fun createVersion(
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: MacroDefinitionRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.createVersion(
        macroId,
        parseMacroEtag(ifMatch),
        body.toDraft(),
        request.actor(principal, RequestSource.ADMIN_UI),
    ))

    @PutMapping("/{macroId}/activation")
    fun activate(
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: MacroActivationRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.activate(
        macroId,
        body.version,
        parseMacroEtag(ifMatch),
        request.actor(principal, RequestSource.ADMIN_UI),
    ))

    @DeleteMapping("/{macroId}/activation")
    fun deactivate(
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ) = response(administration.deactivate(
        macroId,
        parseMacroEtag(ifMatch),
        request.actor(principal, RequestSource.ADMIN_UI),
    ))
}

internal data class MacroDefinitionRequest(
    @field:NotBlank @field:Size(max = 120) val name: String,
    @field:NotEmpty @field:Size(max = 50) @field:Valid val actions: List<MacroActionRequest>,
) {
    fun toDraft() = MacroDefinitionDraft(name, actions.map(MacroActionRequest::toDefinition))
}

internal data class MacroActionRequest(
    @field:NotNull val type: MacroActionType,
    val status: TicketStatus? = null,
    val priority: TicketPriority? = null,
    val groupId: UUID? = null,
    val assigneeId: UUID? = null,
    val tagId: UUID? = null,
    @field:Size(max = 120) val fieldKey: String? = null,
    @field:Valid val value: TicketConfigurationFieldValue? = null,
    val customStatusId: UUID? = null,
    val visibility: CommentVisibility? = null,
    @field:Size(max = 10_000) val template: String? = null,
) {
    fun toDefinition(): MacroActionDefinition = when (type) {
        MacroActionType.STATUS -> MacroStatusAction(required(status, "status"))
        MacroActionType.PRIORITY -> MacroPriorityAction(required(priority, "priority"))
        MacroActionType.GROUP -> MacroGroupAction(required(groupId, "groupId"))
        MacroActionType.ASSIGNEE -> MacroAssigneeAction(assigneeId)
        MacroActionType.ADD_TAG -> MacroAddTagAction(required(tagId, "tagId"))
        MacroActionType.REMOVE_TAG -> MacroRemoveTagAction(required(tagId, "tagId"))
        MacroActionType.CUSTOM_FIELD -> MacroCustomFieldAction(required(fieldKey, "fieldKey"), required(value, "value"))
        MacroActionType.CUSTOM_STATUS -> MacroCustomStatusAction(required(customStatusId, "customStatusId"))
        MacroActionType.COMMENT -> MacroCommentAction(required(visibility, "visibility"), required(template, "template"))
    }

    private fun <T> required(value: T?, name: String): T = value
        ?: throw MacroValidationException("MACRO_ACTION_CONFIGURATION_INVALID", "$name is required for $type")
}

internal data class MacroActivationRequest(@field:NotNull val version: Int)

private fun HttpServletRequest.actor(principal: StaffPrincipal, source: RequestSource): MacroDefinitionActor {
    val context = CommandContexts.from(this, source)
    return MacroDefinitionActor(
        staffId = principal.id,
        displayName = principal.displayName,
        isAdmin = principal.role == StaffRole.ADMIN,
        authorities = principal.authorities,
        source = source,
        requestId = context.requestId,
        correlationId = context.correlationId,
    )
}

private fun parseMacroEtag(value: String): Long {
    val match = Regex("\\\"(\\d+)\\\"").matchEntire(value)
        ?: throw MacroValidationException("INVALID_ETAG", "If-Match must be a quoted decimal version")
    return match.groupValues[1].toLongOrNull()
        ?: throw MacroValidationException("INVALID_ETAG", "If-Match is invalid")
}

private fun response(view: MacroDefinitionView): ResponseEntity<MacroDefinitionView> =
    ResponseEntity.ok().eTag(view.aggregateVersion.toString()).body(view)
