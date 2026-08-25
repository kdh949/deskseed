package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Positive
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets/{ticketNumber}/macros")
@Validated
internal class AgentMacroPreviewController(
    private val applicationService: MacroPreviewApplicationService,
) {
    @PostMapping("/{macroId}/preview")
    fun preview(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable macroId: UUID,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<MacroPreviewResult> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(applicationService.preview(principal, ticketNumber, macroId, interactionId, request.readContext()))

    private fun HttpServletRequest.readContext() = AgentReadRequestContext(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        sessionId = getSession(false)?.id
            ?: throw AccessAuditUnavailableException(IllegalStateException("Authenticated staff session is unavailable")),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
    )
}
