package dev.deskseed.staffaccess.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets")
@Validated
internal class AgentTicketBatchController(
    private val applicationService: AgentTicketBatchApplicationService,
) {
    @PostMapping("/batch-commands")
    fun execute(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: AgentTicketBatchCommandRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AgentTicketBatchResultResponse> {
        val result = applicationService.execute(principal, body.items.map(AgentTicketBatchItemRequest::toInput), request)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                AgentTicketBatchResultResponse(
                    correlationId = result.correlationId,
                    results = result.results.map(::toResponse),
                ),
            )
    }

    private fun toResponse(result: AgentTicketBatchItemResult) = AgentTicketBatchItemResultResponse(
        ticketNumber = result.ticketNumber,
        clientCommandId = result.clientCommandId,
        outcome = result.outcome.name,
        replayed = result.replayed,
        resultVersion = result.resultVersion,
        auditId = result.auditId,
        code = result.code,
    )
}

internal data class AgentTicketBatchCommandRequest(
    @field:Size(min = 1, max = 100)
    @field:Valid
    val items: List<AgentTicketBatchItemRequest>,
)

internal data class AgentTicketBatchItemRequest(
    @field:Positive val ticketNumber: Long,
    @field:PositiveOrZero val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 100)
    @field:Pattern(regexp = "^[A-Za-z0-9._:-]+$")
    val clientCommandId: String,
    val command: JsonNode,
) {
    fun toInput() = AgentTicketBatchItemInput(ticketNumber, expectedVersion, clientCommandId, command)
}

internal data class AgentTicketBatchResultResponse(
    val correlationId: String,
    val results: List<AgentTicketBatchItemResultResponse>,
)

internal data class AgentTicketBatchItemResultResponse(
    val ticketNumber: Long,
    val clientCommandId: String,
    val outcome: String,
    val replayed: Boolean,
    val resultVersion: Long?,
    val auditId: UUID?,
    val code: String?,
)
