package dev.deskseed.staffaccess.internal

import dev.deskseed.audit.AccessAuditSessionFingerprint
import dev.deskseed.audit.AuditActivityDetail
import dev.deskseed.audit.AuditActivityFilter
import dev.deskseed.audit.AuditActivityPage
import dev.deskseed.audit.AuditExplorer
import dev.deskseed.audit.AuditExplorerOutcome
import dev.deskseed.audit.AuditExportFormat
import dev.deskseed.audit.AuditExportJob
import dev.deskseed.audit.AuditFieldChange
import dev.deskseed.audit.AuditLedgerType
import dev.deskseed.audit.AuditProjectionRebuildResult
import dev.deskseed.audit.AuditRequestContext
import dev.deskseed.audit.AuditSearchContext
import dev.deskseed.audit.SearchQueryRevealResult
import dev.deskseed.audit.CreateAuditExportCommand
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.foundation.RequestSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/audit")
@Validated
internal class AuditExplorerController(
    private val auditExplorer: AuditExplorer,
    private val sessionFingerprint: AccessAuditSessionFingerprint,
) {
    @GetMapping("/activities")
    fun activities(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) ledger: AuditLedgerType?,
        @RequestParam(required = false) @Size(min = 1, max = 80) action: String?,
        @RequestParam(required = false) actorType: ActorType?,
        @RequestParam(required = false) actorId: UUID?,
        @RequestParam(required = false) @Positive ticketNumber: Long?,
        @RequestParam(required = false) groupId: UUID?,
        @RequestParam(required = false) @Size(min = 1, max = 60) field: String?,
        @RequestParam(required = false) source: RequestSource?,
        @RequestParam(required = false) outcome: AuditExplorerOutcome?,
        @RequestParam(required = false) @Size(min = 1, max = 100) requestId: String?,
        @RequestParam(required = false) @Size(min = 1, max = 100) correlationId: String?,
        @RequestParam(required = false) @Size(min = 1, max = 100) searchFingerprint: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        request: HttpServletRequest,
    ): ResponseEntity<AuditActivityPage> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            auditExplorer.list(
                filters = AuditActivityFilter(
                    from = from,
                    to = to,
                    ledger = ledger,
                    action = action,
                    actorType = actorType,
                    actorId = actorId,
                    ticketNumber = ticketNumber,
                    groupId = groupId,
                    field = field,
                    source = source?.name,
                    outcome = outcome,
                    requestId = requestId,
                    correlationId = correlationId,
                    searchFingerprint = searchFingerprint,
                ),
                cursor = cursor,
                limit = limit,
                context = request.auditContext(principal, interactionId),
            ),
        )

    @GetMapping("/activities/{activityId}")
    fun activity(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable activityId: UUID,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<AuditActivityDetailResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(auditExplorer.detail(activityId, request.auditContext(principal, interactionId)).toResponse())

    @PostMapping("/activities/{activityId}/search-query-reveal")
    fun revealSearchQuery(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable activityId: UUID,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @Valid @RequestBody body: SearchQueryRevealRequest,
        request: HttpServletRequest,
    ): ResponseEntity<SearchQueryRevealResult> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            auditExplorer.revealSearchQuery(
                activityId = activityId,
                reason = body.reason,
                context = request.auditContext(principal, interactionId),
            ),
        )

    @PostMapping("/exports")
    fun createExport(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        @Valid @RequestBody body: CreateAuditExportRequest,
        request: HttpServletRequest,
    ): ResponseEntity<AuditExportJob> = ResponseEntity.accepted()
        .cacheControl(CacheControl.noStore())
        .body(
            auditExplorer.createExport(
                command = CreateAuditExportCommand(
                    format = body.format,
                    filters = body.filters.toFilter(),
                    fields = body.fields,
                    reason = body.reason,
                ),
                context = request.auditContext(principal, interactionId),
            ),
        )

    @GetMapping("/exports/{jobId}")
    fun export(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @PathVariable jobId: UUID,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<AuditExportJob> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(auditExplorer.getExport(jobId, request.auditContext(principal, interactionId)))

    @PostMapping("/projection/rebuild")
    fun rebuild(
        @AuthenticationPrincipal principal: StaffPrincipal,
        @RequestHeader("X-Interaction-Id") interactionId: UUID,
        request: HttpServletRequest,
    ): ResponseEntity<AuditProjectionRebuildResult> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(auditExplorer.rebuild(request.auditContext(principal, interactionId)))

    private fun HttpServletRequest.auditContext(
        principal: StaffPrincipal,
        interactionId: UUID,
    ): AuditRequestContext {
        val session = getSession(false) ?: throw AccessAuditUnavailableException(
            IllegalStateException("Authenticated staff session is unavailable"),
        )
        return AuditRequestContext(
            actorId = principal.id,
            actorDisplayName = principal.displayName,
            authorities = principal.authorities,
            requestId = getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "missing-request-id",
            correlationId = getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)?.toString() ?: "missing-correlation-id",
            interactionId = interactionId,
            sessionFingerprint = sessionFingerprint.fingerprint(session.id),
            ipAddress = remoteAddr,
            userAgent = getHeader("User-Agent"),
            authenticatedAt = session.getAttribute(StaffSessionValidationFilter.AUTHENTICATED_AT) as? Instant,
            mfaVerifiedAt = session.getAttribute(StaffSessionValidationFilter.MFA_VERIFIED_AT) as? Instant,
        )
    }
}

internal data class SearchQueryRevealRequest(
    @field:Size(max = 1000)
    val reason: String,
)

internal data class CreateAuditExportRequest(
    val format: AuditExportFormat,
    @field:Valid
    val filters: AuditExportFiltersRequest = AuditExportFiltersRequest(),
    @field:Size(min = 1, max = 20)
    val fields: List<String>,
    @field:Size(min = 1, max = 1000)
    val reason: String,
)

internal data class AuditExportFiltersRequest(
    val from: Instant? = null,
    val to: Instant? = null,
    val ledger: AuditLedgerType? = null,
    @field:Size(max = 80)
    val action: String? = null,
    val actorType: ActorType? = null,
    val actorId: UUID? = null,
    @field:Positive
    val ticketNumber: Long? = null,
    val groupId: UUID? = null,
    @field:Size(max = 60)
    val field: String? = null,
    val source: RequestSource? = null,
    val outcome: AuditExplorerOutcome? = null,
    @field:Size(max = 100)
    val requestId: String? = null,
    @field:Size(max = 100)
    val correlationId: String? = null,
    @field:Size(max = 100)
    val searchFingerprint: String? = null,
) {
    fun toFilter() = AuditActivityFilter(
        from,
        to,
        ledger,
        action,
        actorType,
        actorId,
        ticketNumber,
        groupId,
        field,
        source?.name,
        outcome,
        requestId,
        correlationId,
        searchFingerprint,
    )
}

internal data class AuditActivityDetailResponse(
    val id: UUID,
    val ledger: AuditLedgerType,
    val action: String,
    val actor: dev.deskseed.audit.AuditExplorerActor,
    val occurredAt: Instant,
    val ticketNumber: Long?,
    val groupId: UUID?,
    val field: String?,
    val resourceType: String?,
    val resourceId: UUID?,
    val summary: String,
    val source: String,
    val outcome: AuditExplorerOutcome,
    val requestId: String?,
    val correlationId: String?,
    val protectedContentAvailable: Boolean,
    val searchFingerprint: String?,
    val canonicalEventId: UUID,
    val canonicalParentId: UUID?,
    val fieldChange: AuditFieldChange?,
    val interactionId: UUID?,
    val sessionFingerprint: String?,
    val authType: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val search: AuditSearchContext?,
    val metadata: Map<String, Any?>,
)

private fun AuditActivityDetail.toResponse(): AuditActivityDetailResponse = activity.run {
    AuditActivityDetailResponse(
        id = id,
        ledger = ledger,
        action = action,
        actor = actor,
        occurredAt = occurredAt,
        ticketNumber = ticketNumber,
        groupId = groupId,
        field = field,
        resourceType = resourceType,
        resourceId = resourceId,
        summary = summary,
        source = source,
        outcome = outcome,
        requestId = requestId,
        correlationId = correlationId,
        protectedContentAvailable = protectedContentAvailable,
        searchFingerprint = searchFingerprint,
        canonicalEventId = this@toResponse.canonicalEventId,
        canonicalParentId = this@toResponse.canonicalParentId,
        fieldChange = this@toResponse.fieldChange,
        interactionId = this@toResponse.interactionId,
        sessionFingerprint = this@toResponse.sessionFingerprint,
        authType = this@toResponse.authType,
        ipAddress = this@toResponse.ipAddress,
        userAgent = this@toResponse.userAgent,
        search = this@toResponse.search,
        metadata = this@toResponse.metadata,
    )
}
