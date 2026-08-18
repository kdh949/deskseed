package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.TicketCommandInvalidException
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketconfiguration.TicketConfigurationDescriptorView
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeQuery
import dev.deskseed.ticketconfiguration.TicketConfigurationRuntimeValues
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/** HTTP translation only; the ticketing command owns authorization, replay, audit, and rollback. */
@RestController
@RequestMapping("/api/v1/agent")
@Validated
internal class AgentTicketConfigurationController(
    private val applicationService: AgentTicketCommandApplicationService,
    private val ticketReadApplicationService: AgentTicketReadApplicationService,
    private val runtimeQuery: TicketConfigurationRuntimeQuery,
) {
    @GetMapping("/ticket-configuration/descriptors")
    fun descriptors(@AuthenticationPrincipal principal: StaffPrincipal): ResponseEntity<List<TicketConfigurationDescriptorView>> {
        // A valid staff session is established before controller dispatch; descriptors contain no values.
        require(principal.id != UUID(0, 0)) { "Active staff principal is required" }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runtimeQuery.listAgentDescriptors())
    }

    @GetMapping("/tickets/{ticketNumber}/configuration")
    fun read(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        request: HttpServletRequest,
    ): ResponseEntity<TicketConfigurationRuntimeValues> {
        val workspace = ticketReadApplicationService.readTicket(
            principal = principal,
            ticketNumber = ticketNumber,
            interactionId = UUID.randomUUID(),
            intent = AgentReadIntent.BACKGROUND,
            originSearchEventId = null,
            context = AgentReadRequestContext(
                requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
                correlationId = request.getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
                sessionId = request.getSession(false)?.id
                    ?: throw AccessAuditUnavailableException(IllegalStateException("Authenticated staff session is unavailable")),
                ipAddress = request.remoteAddr,
                userAgent = request.getHeader("User-Agent"),
            ),
        )
        val ticket = workspace.detail.ticket
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .eTag(ticket.version.toString())
            .body(runtimeQuery.readAgentConfiguration(ticket.id, ticket.ticketNumber, ticket.version, ticket.status))
    }

    @PutMapping("/tickets/{ticketNumber}/configuration")
    fun update(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: UpdateTicketConfigurationRequest,
        request: HttpServletRequest,
    ): ResponseEntity<TicketConfigurationCommandResponse> {
        val expectedVersion = expectedVersion(ifMatch)
        if (body.addTagIds.size != body.addTagIds.toSet().size || body.removeTagIds.size != body.removeTagIds.toSet().size) {
            throw TicketCommandInvalidException("Tag ID collections must be unique")
        }
        if ((body.addTagIds.toSet() intersect body.removeTagIds.toSet()).isNotEmpty()) {
            throw TicketCommandInvalidException("A tag cannot be added and removed in one command")
        }
        val result = applicationService.updateConfiguration(
            principal = principal,
            ticketNumber = ticketNumber,
            input = UpdateTicketConfigurationInput(
                expectedVersion = expectedVersion,
                formVersion = body.formVersion,
                fieldValues = body.fieldValues.mapValues { (_, value) -> value.toCommandValue() },
                addTagIds = body.addTagIds.toSet(),
                removeTagIds = body.removeTagIds.toSet(),
                customStatusId = body.customStatusId,
            ),
            context = CommandContexts.from(request, RequestSource.AGENT_UI).copy(commandId = body.clientCommandId.toString()),
        )
        return ResponseEntity.ok()
            .eTag(result.version.toString())
            .body(TicketConfigurationCommandResponse(result.ticketNumber, result.version, result.auditId, result.replayed))
    }

    private fun expectedVersion(ifMatch: String): Long = ifMatch.trim().removeSurrounding("\"").toLongOrNull()
            ?: throw TicketCommandInvalidException("If-Match must contain a numeric ticket ETag")
}

internal data class UpdateTicketConfigurationRequest(
    @field:Positive val formVersion: Int? = null,
    @field:Size(max = 100) @field:Valid val fieldValues: Map<String, TicketConfigurationFieldValueRequest> = emptyMap(),
    @field:Size(max = 50) val addTagIds: List<UUID> = emptyList(),
    @field:Size(max = 50) val removeTagIds: List<UUID> = emptyList(),
    val customStatusId: UUID? = null,
    @field:NotNull val clientCommandId: UUID,
)

internal data class TicketConfigurationFieldValueRequest(
    val booleanValue: Boolean? = null,
    val numberValue: BigDecimal? = null,
    val optionId: UUID? = null,
    @field:Size(max = 1_000) val shortTextValue: String? = null,
    @field:Size(max = 10_000) val longTextValue: String? = null,
) {
    fun toCommandValue(): TicketConfigurationFieldValue = try {
        TicketConfigurationFieldValue(
            booleanValue = booleanValue,
            numberValue = numberValue?.toPlainString(),
            optionId = optionId,
            shortTextValue = shortTextValue,
            longTextValue = longTextValue,
        )
    } catch (failure: IllegalArgumentException) {
        throw TicketCommandInvalidException(failure.message ?: "Ticket configuration value must be typed")
    }
}

internal data class TicketConfigurationCommandResponse(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val replayed: Boolean,
)
