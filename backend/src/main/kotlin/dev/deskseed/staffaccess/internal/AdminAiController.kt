package dev.deskseed.staffaccess.internal

import dev.deskseed.aiassistance.AiAdministrationService
import dev.deskseed.aiassistance.AiRequestMetadata
import dev.deskseed.aiassistance.AiReplyRoutingMode
import dev.deskseed.aiassistance.AiSettingsView
import dev.deskseed.aiassistance.AiStatusView
import dev.deskseed.aiassistance.UpdateAiSettingsCommand
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/ai")
internal class AdminAiController(private val service: AiAdministrationService) {
    @GetMapping("/status")
    fun status(): ResponseEntity<AiStatusView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.status())

    @GetMapping("/settings")
    fun settings(): ResponseEntity<AiSettingsView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(service.settings())

    @PutMapping("/settings")
    fun update(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: UpdateAiSettingsRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AiSettingsView> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            service.update(
                UpdateAiSettingsCommand(
                    enabled = body.enabled,
                    summaryEnabled = body.summaryEnabled,
                    triageEnabled = body.triageEnabled,
                    replyDraftEnabled = body.replyDraftEnabled,
                    replyRewriteEnabled = body.replyRewriteEnabled,
                    fastModelAlias = body.fastModelAlias,
                    standardModelAlias = body.standardModelAlias,
                    replyRoutingMode = body.replyRoutingMode,
                    replyRoutingCohorts = body.replyRoutingCohorts,
                    replyRoutingRolloutPercent = body.replyRoutingRolloutPercent,
                    replyRoutingEvaluationApprovalVersion =
                        body.replyRoutingEvaluationApprovalVersion,
                    allowedStaffIds = body.allowedStaffIds,
                    expectedVersion = body.expectedVersion,
                    actorId = principal.id,
                    actorDisplayName = principal.displayName,
                    metadata = request.metadata(),
                ),
            ),
        )

    @PostMapping("/kb/reindex")
    fun reindex(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @Valid @RequestBody body: AiReindexRequest,
        request: HttpServletRequest,
    ) = ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(
        service.reindex(body.operationId, principal.id, principal.displayName, request.metadata()),
    )

    private fun HttpServletRequest.metadata() = AiRequestMetadata(
        requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE).toString(),
        correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE).toString(),
        ipAddress = remoteAddr,
        userAgent = getHeader("User-Agent"),
    )
}

internal data class UpdateAiSettingsRequest(
    val enabled: Boolean,
    val summaryEnabled: Boolean,
    val triageEnabled: Boolean,
    val replyDraftEnabled: Boolean,
    val replyRewriteEnabled: Boolean,
    @field:NotBlank @field:Size(max = 100)
    val fastModelAlias: String,
    @field:NotBlank @field:Size(max = 100)
    val standardModelAlias: String,
    val replyRoutingMode: AiReplyRoutingMode,
    @field:Size(max = 1)
    val replyRoutingCohorts: Set<String>,
    val replyRoutingRolloutPercent: Int,
    @field:Size(max = 80)
    @field:Pattern(regexp = "^[a-z0-9][a-z0-9._-]*$")
    val replyRoutingEvaluationApprovalVersion: String?,
    @field:Size(max = 1000)
    val allowedStaffIds: Set<UUID>,
    @field:PositiveOrZero
    val expectedVersion: Long,
)

internal data class AiReindexRequest(val operationId: UUID)
