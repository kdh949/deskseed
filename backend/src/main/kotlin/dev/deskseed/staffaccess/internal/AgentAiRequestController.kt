package dev.deskseed.staffaccess.internal

import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.aiassistance.AiFeedbackType
import dev.deskseed.aiassistance.AiFeedbackReceipt
import dev.deskseed.aiassistance.AiJobReceipt
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiRequestService
import dev.deskseed.aiassistance.AiStaffActor
import dev.deskseed.aiassistance.CreateAiRequestCommand
import dev.deskseed.aiassistance.RecordAiFeedbackCommand
import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/agent/tickets/{ticketNumber}/ai/jobs")
@Validated
internal class AgentAiRequestController(
    private val requestService: AiRequestService,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
) {
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody body: CreateAgentAiJobRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AgentAiJobResponse> {
        val receipt = requestService.create(
            CreateAiRequestCommand(
                ticketNumber = ticketNumber,
                feature = AiFeature.fromValue(body.feature),
                expectedTicketVersion = body.expectedTicketVersion,
                options = body.options,
                idempotencyKey = idempotencyKey,
                actor = request.actor(principal),
                metadata = request.metadata(),
            ),
        )
        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .body(receipt.toResponse())
    }

    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ): ResponseEntity<AgentAiJobPageResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(AgentAiJobPageResponse(requestService.list(ticketNumber, principal.id, limit).map(AiJobReceipt::toResponse)))

    @GetMapping("/{jobId}")
    fun get(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable jobId: UUID,
        @RequestParam(defaultValue = "false") includeResult: Boolean,
        request: HttpServletRequest,
    ): ResponseEntity<AgentAiJobResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(requestService.get(ticketNumber, jobId, includeResult, request.actor(principal), request.metadata()).toResponse())

    @PostMapping("/{jobId}/cancel")
    fun cancel(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable jobId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<AgentAiJobResponse> = ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(requestService.cancel(ticketNumber, jobId, request.actor(principal), request.metadata()).toResponse())

    @PostMapping("/{jobId}/feedback")
    fun feedback(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @PathVariable jobId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody body: RecordAgentAiFeedbackRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AiFeedbackReceipt> = ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(
            requestService.feedback(
                RecordAiFeedbackCommand(
                    ticketNumber = ticketNumber,
                    jobId = jobId,
                    type = AiFeedbackType.fromValue(body.type),
                    reasonCode = body.reasonCode,
                    idempotencyKey = idempotencyKey,
                    actor = request.actor(principal),
                    metadata = request.metadata(),
                ),
            ),
        )

    private fun HttpServletRequest.actor(principal: StaffPrincipal): AiStaffActor {
        val sessionId = getSession(false)?.id
            ?: throw IllegalStateException("Authenticated staff session is unavailable")
        return AiStaffActor(principal.id, principal.displayName, sessionFingerprint.fingerprint(sessionId))
    }

    private fun HttpServletRequest.metadata() = AiRequestMetadata(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
        traceparent = getHeader("traceparent")?.take(512),
        tracestate = getHeader("tracestate")?.take(512),
    )
}

internal data class CreateAgentAiJobRequest(
    @field:NotBlank @field:Size(max = 64)
    val feature: String,
    @field:PositiveOrZero
    val expectedTicketVersion: Long,
    @field:Size(max = 2)
    val options: Map<@NotBlank @Size(max = 40) String, @NotBlank @Size(max = 40) String> = emptyMap(),
)

internal data class RecordAgentAiFeedbackRequest(
    @field:NotBlank @field:Size(max = 24)
    val type: String,
    @field:Size(max = 40)
    val reasonCode: String? = null,
)

internal data class AgentAiJobResponse(
    val jobId: UUID,
    val feature: String,
    val status: String,
    val requestRevision: Long,
    val createdAt: Instant,
    val deadlineAt: Instant,
    val pollAfterMs: Long,
    val cancelRequested: Boolean,
    val contextRevision: String,
    val contextPolicyVersion: String,
    val inputScope: String,
    val phase: String,
    val generation: Int?,
    val leaseEpoch: Long?,
    val completedAt: Instant?,
    val resultExpiresAt: Instant?,
    val stale: Boolean,
    val canInsert: Boolean,
    val errorCode: String?,
    val result: dev.deskseed.aiassistance.AiTypedResult?,
    val provenance: dev.deskseed.aiassistance.AiGenerationProvenance?,
    val costMicrousd: Long?,
)

internal data class AgentAiJobPageResponse(val items: List<AgentAiJobResponse>)

private fun AiJobReceipt.toResponse() = AgentAiJobResponse(
    jobId = jobId,
    feature = feature,
    status = status.name,
    requestRevision = requestRevision,
    createdAt = createdAt,
    deadlineAt = deadlineAt,
    pollAfterMs = pollAfterMs,
    cancelRequested = cancelRequested,
    contextRevision = contextRevision,
    contextPolicyVersion = contextPolicyVersion,
    inputScope = "PUBLIC_ONLY",
    phase = phase,
    generation = generation,
    leaseEpoch = leaseEpoch,
    completedAt = completedAt,
    resultExpiresAt = resultExpiresAt,
    stale = stale,
    canInsert = canInsert,
    errorCode = errorCode,
    result = result,
    provenance = provenance,
    costMicrousd = costMicrousd,
)
