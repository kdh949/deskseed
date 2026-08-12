package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.sla.FirstReplyPolicyConditions
import dev.deskseed.sla.FirstReplySlaAdministration
import dev.deskseed.sla.FirstReplySlaPolicyDefinition
import dev.deskseed.sla.FirstReplySlaPolicyValidationException
import dev.deskseed.sla.FirstReplySlaPolicyView
import dev.deskseed.sla.FirstReplySlaPreview
import dev.deskseed.sla.FirstReplySlaTicketSample
import dev.deskseed.sla.SlaAdminActor
import dev.deskseed.ticketing.TicketChannel
import dev.deskseed.ticketing.TicketPriority
import dev.deskseed.ticketing.TicketStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
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
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/sla-policies")
@Validated
internal class AdminFirstReplySlaController(
    private val administration: FirstReplySlaAdministration,
) {
    @GetMapping
    fun list(): List<FirstReplySlaPolicyView> = administration.list()

    @PostMapping
    fun create(
        @Valid @RequestBody body: FirstReplySlaPolicyRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<FirstReplySlaPolicyView> {
        val created = administration.create(body.toDefinition(), request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/sla-policies/${created.id}"))
            .eTag(created.aggregateVersion.toString())
            .body(created)
    }

    @PostMapping("/preview")
    fun preview(@Valid @RequestBody body: FirstReplySlaPreviewRequest): FirstReplySlaPreview =
        administration.preview(body.candidate?.toDefinition(), body.ticket.toSample(), body.startAt)

    @GetMapping("/{policyId}")
    fun get(@PathVariable policyId: UUID): ResponseEntity<FirstReplySlaPolicyView> {
        val policy = administration.get(policyId)
        return ResponseEntity.ok().eTag(policy.aggregateVersion.toString()).body(policy)
    }

    @GetMapping("/{policyId}/versions")
    fun versions(@PathVariable policyId: UUID): List<FirstReplySlaPolicyView> =
        administration.listVersions(policyId)

    @PostMapping("/{policyId}/versions")
    fun createVersion(
        @PathVariable policyId: UUID,
        @RequestHeader("If-Match") ifMatch: String,
        @Valid @RequestBody body: FirstReplySlaPolicyRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<FirstReplySlaPolicyView> {
        val created = administration.createVersion(
            policyId,
            parseEtag(ifMatch),
            body.toDefinition(),
            request.actor(principal),
        )
        return ResponseEntity.created(URI.create("/api/v1/admin/sla-policies/$policyId/versions/${created.version}"))
            .eTag(created.aggregateVersion.toString())
            .body(created)
    }

    @PutMapping("/{policyId}/versions/{version}/activation")
    fun activate(
        @PathVariable policyId: UUID,
        @PathVariable @Min(1) version: Int,
        @RequestHeader("If-Match") ifMatch: String,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<FirstReplySlaPolicyView> {
        val activated = administration.activate(
            policyId,
            version,
            parseEtag(ifMatch),
            request.actor(principal),
        )
        return ResponseEntity.ok().eTag(activated.aggregateVersion.toString()).body(activated)
    }

    private fun parseEtag(value: String): Long {
        val match = ETAG.matchEntire(value)
            ?: throw FirstReplySlaPolicyValidationException("If-Match", "INVALID_ETAG", "If-Match must be quoted")
        return match.groupValues[1].toLongOrNull()
            ?: throw FirstReplySlaPolicyValidationException("If-Match", "INVALID_ETAG", "If-Match is invalid")
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal): SlaAdminActor {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return SlaAdminActor(
            principal.id,
            principal.displayName,
            context.source,
            context.requestId,
            context.correlationId,
        )
    }

    private companion object {
        val ETAG = Regex("\"(\\d+)\"")
    }
}

internal data class FirstReplySlaPolicyRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Min(1) @field:Max(10_000) val position: Int,
    val scheduleId: UUID,
    @field:Valid val conditions: FirstReplySlaPolicyConditionsRequest = FirstReplySlaPolicyConditionsRequest(),
    @field:Valid val targets: FirstReplySlaPriorityTargetsRequest,
    @field:Size(max = 6) val pauseStatuses: Set<TicketStatus> = setOf(TicketStatus.PENDING),
) {
    fun toDefinition() = FirstReplySlaPolicyDefinition(
        name = name,
        position = position,
        scheduleId = scheduleId,
        conditions = FirstReplyPolicyConditions(conditions.groupId, conditions.channel),
        targets = targets.toMap(),
        pauseStatuses = pauseStatuses,
    )
}

internal data class FirstReplySlaPolicyConditionsRequest(
    val groupId: UUID? = null,
    val channel: TicketChannel? = null,
)

internal data class FirstReplySlaPriorityTargetsRequest(
    @field:Min(1) @field:Max(525_600) val LOW: Long? = null,
    @field:Min(1) @field:Max(525_600) val NORMAL: Long? = null,
    @field:Min(1) @field:Max(525_600) val HIGH: Long? = null,
    @field:Min(1) @field:Max(525_600) val URGENT: Long? = null,
) {
    fun toMap(): Map<TicketPriority, Long> = buildMap {
        LOW?.let { put(TicketPriority.LOW, it) }
        NORMAL?.let { put(TicketPriority.NORMAL, it) }
        HIGH?.let { put(TicketPriority.HIGH, it) }
        URGENT?.let { put(TicketPriority.URGENT, it) }
    }
}

internal data class FirstReplySlaPreviewRequest(
    @field:Valid val candidate: FirstReplySlaPolicyRequest? = null,
    @field:Valid val ticket: FirstReplySlaTicketSampleRequest,
    val startAt: Instant,
)

internal data class FirstReplySlaTicketSampleRequest(
    val priority: TicketPriority,
    val groupId: UUID? = null,
    val channel: TicketChannel,
) {
    fun toSample() = FirstReplySlaTicketSample(priority, groupId, channel)
}
