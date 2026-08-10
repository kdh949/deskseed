package dev.deskseed.portal.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.ticketing.CustomerRequestStatus
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/requests")
internal class PublicRequestController(
    private val applicationService: PublicRequestApplicationService,
) {
    @PostMapping
    fun submit(
        @Valid @RequestBody body: SubmitRequestBody,
        request: HttpServletRequest,
    ): ResponseEntity<SubmittedRequestResponse> {
        val result = applicationService.submit(
            SubmitAnonymousRequest(
                name = body.name,
                email = body.email,
                subject = body.subject,
                message = body.message,
                context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
            ),
        )

        return ResponseEntity
            .created(URI.create("/api/v1/requests/${result.ticketNumber}"))
            .body(
                SubmittedRequestResponse(
                    ticketNumber = result.ticketNumber,
                    status = result.status,
                    accessToken = result.accessToken,
                    createdAt = result.createdAt,
                ),
            )
    }

    @GetMapping("/{ticketNumber}")
    fun view(
        @PathVariable ticketNumber: Long,
        @RequestHeader("X-Request-Access-Token") accessToken: String,
    ): ResponseEntity<PublicRequestResponse> {
        val ticket = applicationService.view(ticketNumber, accessToken)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                PublicRequestResponse(
                    ticketNumber = ticket.ticketNumber,
                    subject = ticket.subject,
                    status = ticket.status,
                    createdAt = ticket.createdAt,
                    updatedAt = ticket.updatedAt,
                    comments = ticket.comments.map {
                        PublicCommentResponse(
                            id = it.id,
                            authorDisplayName = it.authorDisplayName,
                            body = it.body,
                            createdAt = it.createdAt,
                        )
                    },
                ),
            )
    }
}

internal data class SubmitRequestBody(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,

    @field:NotBlank
    @field:Size(max = 200)
    val subject: String,

    @field:NotBlank
    @field:Size(max = 20_000)
    val message: String,

    val privacyConsent: Boolean? = null,
)

internal data class SubmittedRequestResponse(
    val ticketNumber: Long,
    val status: CustomerRequestStatus,
    val accessToken: String,
    val createdAt: Instant,
)

internal data class PublicRequestResponse(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val comments: List<PublicCommentResponse>,
)

internal data class PublicCommentResponse(
    val id: UUID,
    val authorDisplayName: String,
    val body: String,
    val createdAt: Instant,
)
