package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets/{ticketNumber}/macros")
@Validated
internal class AgentMacroApplyController(
    private val applicationService: MacroApplyApplicationService,
) {
    @PostMapping("/{macroId}/apply")
    fun apply(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable macroId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: MacroApplyRequest,
        request: HttpServletRequest,
    ): ResponseEntity<MacroApplyResponse> {
        val result = applicationService.apply(
            principal = principal,
            ticketNumber = ticketNumber,
            macroId = macroId,
            expectedTicketVersion = parseTicketEtag(ifMatch),
            macroVersion = body.macroVersion,
            commentBodyOverride = body.commentBodyOverride,
            context = CommandContexts.from(request, RequestSource.AGENT_UI).copy(commandId = body.clientCommandId.toString()),
        )
        return ResponseEntity.ok().eTag(result.version.toString()).body(
            MacroApplyResponse(
                ticketNumber = result.ticketNumber,
                version = result.version,
                auditId = result.auditId,
                replayed = result.replayed,
                warnings = result.warnings.map {
                    TicketCommandWarningResponse(it.code, it.message, it.count, it.relatedTicketNumbers)
                },
            ),
        )
    }

    private fun parseTicketEtag(value: String): Long = value.trim().removeSurrounding("\"").toLongOrNull()
        ?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("If-Match must contain a non-negative ticket version")
}

internal data class MacroApplyRequest(
    @field:Positive val macroVersion: Int,
    @field:Size(min = 1, max = 20_000) val commentBodyOverride: String? = null,
    @field:NotNull val clientCommandId: UUID,
)

internal data class MacroApplyResponse(
    val ticketNumber: Long,
    val version: Long,
    val auditId: UUID,
    val replayed: Boolean,
    val warnings: List<TicketCommandWarningResponse>,
)
