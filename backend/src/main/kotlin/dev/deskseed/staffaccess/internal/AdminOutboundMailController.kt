package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.ActorRef
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.outboundmail.ManualMailRetryCommand
import dev.deskseed.outboundmail.OutboundMailIntentListQuery
import dev.deskseed.outboundmail.OutboundMailIntentStatus
import dev.deskseed.outboundmail.OutboundMailIntentView
import dev.deskseed.outboundmail.OutboundMailOperations
import dev.deskseed.outboundmail.OutboundMailOperationsSummary
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** ADMIN-only HTTP boundary for a deliberately masked outbound-mail operational read model. */
@RestController
@RequestMapping("/api/v1/admin/mail")
@Validated
internal class AdminOutboundMailController(
    private val operations: OutboundMailOperations,
) {
    @GetMapping("/summary")
    fun summary(): ResponseEntity<OutboundMailOperationsSummary> = noStore(operations.summary())

    @GetMapping("/intents")
    fun list(
        @RequestParam(required = false) status: OutboundMailIntentStatus?,
        @RequestParam(required = false) @Size(max = 2_000) cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
    ) = noStore(operations.listIntents(OutboundMailIntentListQuery(status, cursor, limit)))

    @GetMapping("/intents/{intentId}")
    fun detail(@PathVariable intentId: UUID): ResponseEntity<OutboundMailIntentView> = noStore(operations.getIntent(intentId))

    @PostMapping("/intents/{intentId}/retry")
    fun retry(
        @PathVariable intentId: UUID,
        @Valid @RequestBody body: RetryOutboundMailIntentRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<OutboundMailIntentView> = noStore(
        operations.retryTerminal(
            ManualMailRetryCommand(
                intentId = intentId,
                actor = ActorRef(dev.deskseed.foundation.ActorType.STAFF, principal.id),
                actorDisplayName = principal.displayName,
                context = CommandContexts.from(request, RequestSource.ADMIN_UI),
                reason = body.reason,
            ),
        ),
    )

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(body)
}

internal data class RetryOutboundMailIntentRequest(
    @field:NotBlank @field:Size(max = 500)
    val reason: String,
)
