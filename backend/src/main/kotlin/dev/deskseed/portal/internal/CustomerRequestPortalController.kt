package dev.deskseed.portal.internal

import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.settings.CustomerAccessMode
import dev.deskseed.ticketing.CustomerRequestStatus
import dev.deskseed.ticketing.CustomerTicketSummary
import dev.deskseed.ticketing.PublicCommentView
import dev.deskseed.ticketing.PublicTicketView
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
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
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@Validated
internal class CustomerRequestPortalController(
    private val applicationService: CustomerRequestPortalApplicationService,
) {
    @GetMapping("/api/v1/customer/access-mode")
    fun accessMode(): ResponseEntity<CustomerAccessModeResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(CustomerAccessModeResponse(applicationService.accessMode()))

    @GetMapping("/api/v1/customer/requests")
    fun list(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @RequestParam(required = false) status: CustomerRequestStatus?,
        @RequestParam(required = false) @Size(max = 512) cursor: String?,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) limit: Int,
    ): ResponseEntity<CustomerRequestPageResponse> {
        val page = applicationService.list(principal, status, cursor, limit)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            CustomerRequestPageResponse(page.items.map(CustomerRequestSummaryResponse::from), page.nextCursor),
        )
    }

    @GetMapping("/api/v1/customer/requests/{ticketNumber}")
    fun detail(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable @Positive ticketNumber: Long,
    ): ResponseEntity<CustomerRequestDetailResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(CustomerRequestDetailResponse.from(applicationService.detail(principal, ticketNumber)))

    @PostMapping("/api/v1/customer/requests/{ticketNumber}/comments")
    fun addFollowUp(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @Valid @RequestBody body: CustomerFollowUpRequest,
        request: HttpServletRequest,
    ): ResponseEntity<CustomerPublicCommentResponse> {
        val result = applicationService.addFollowUp(
            principal,
            ticketNumber,
            body.body,
            body.attachmentIds,
            body.clientCommandId,
            CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        return ResponseEntity.created(
            URI.create("/api/v1/customer/requests/$ticketNumber/comments/${result.comment.id}"),
        ).cacheControl(CacheControl.noStore()).body(CustomerPublicCommentResponse.from(result.comment))
    }

    @PostMapping("/api/v1/requests/{ticketNumber}/claim-grants")
    fun issueClaimGrant(
        @PathVariable @Positive ticketNumber: Long,
        @RequestHeader("X-Request-Access-Token") @Size(min = 32, max = 256) accessToken: String,
    ): ResponseEntity<ClaimGrantResponse> {
        val grant = applicationService.issueClaimGrant(ticketNumber, accessToken)
        return ResponseEntity.created(URI.create("/api/v1/customer/requests/$ticketNumber/claim"))
            .cacheControl(CacheControl.noStore())
            .body(ClaimGrantResponse(grant.token, grant.expiresAt))
    }

    @PostMapping("/api/v1/customer/requests/{ticketNumber}/claim")
    fun claim(
        @AuthenticationPrincipal principal: CustomerPrincipal,
        @PathVariable @Positive ticketNumber: Long,
        @Valid @RequestBody body: ClaimCustomerRequestBody,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val outcome = applicationService.claim(
            principal,
            ticketNumber,
            ClaimCustomerRequestInput(body.requestAccessToken, body.claimToken),
            CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        return when (outcome) {
            ClaimOutcome.CLAIMED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
            ClaimOutcome.NOT_FOUND -> throw dev.deskseed.ticketing.CustomerTicketNotFoundException()
            ClaimOutcome.DENIED -> throw dev.deskseed.ticketing.CustomerTicketClaimDeniedException()
        }
    }
}

internal data class CustomerAccessModeResponse(val mode: CustomerAccessMode)

internal data class CustomerRequestPageResponse(
    val items: List<CustomerRequestSummaryResponse>,
    val nextCursor: String?,
)

internal data class CustomerRequestSummaryResponse(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(value: CustomerTicketSummary) = CustomerRequestSummaryResponse(
            value.ticketNumber,
            value.subject,
            value.status,
            value.createdAt,
            value.updatedAt,
        )
    }
}

internal data class CustomerRequestDetailResponse(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val comments: List<CustomerPublicCommentResponse>,
) {
    companion object {
        fun from(value: PublicTicketView) = CustomerRequestDetailResponse(
            value.ticketNumber,
            value.subject,
            value.status,
            value.createdAt,
            value.updatedAt,
            value.comments.map(CustomerPublicCommentResponse::from),
        )
    }
}

internal data class CustomerPublicCommentResponse(
    val id: UUID,
    val authorDisplayName: String,
    val body: String,
    val createdAt: Instant,
    val attachments: List<dev.deskseed.attachments.TicketAttachment> = emptyList(),
) {
    companion object {
        fun from(value: PublicCommentView) = CustomerPublicCommentResponse(
            value.id,
            value.authorDisplayName,
            value.body,
            value.createdAt,
            value.attachments,
        )
    }
}

internal data class CustomerFollowUpRequest(
    @field:NotBlank @field:Size(max = 20_000) val body: String,
    @field:Size(max = 5) val attachmentIds: List<UUID> = emptyList(),
    @field:NotBlank @field:Size(max = 100) val clientCommandId: String,
)

internal data class ClaimCustomerRequestBody(
    @field:Size(min = 32, max = 1000) val requestAccessToken: String? = null,
    @field:Size(min = 32, max = 1000) val claimToken: String? = null,
)

internal data class ClaimGrantResponse(val claimToken: String, val expiresAt: Instant)
