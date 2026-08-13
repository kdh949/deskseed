package dev.deskseed.portal.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.ticketing.CustomerRequestStatus
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
@Validated
internal class PublicRequestController(
    private val applicationService: PublicRequestApplicationService,
) {
    @PostMapping
    fun submit(
        @Valid @RequestBody body: SubmitRequestBody,
        @AuthenticationPrincipal principal: CustomerPrincipal?,
        request: HttpServletRequest,
    ): ResponseEntity<SubmittedRequestResponse> {
        val result = applicationService.submit(
            SubmitAnonymousRequest(
                name = body.name,
                email = body.email,
                subject = body.subject,
                message = body.message,
                authenticatedCustomerId = principal?.customerId,
                authenticatedEmail = principal?.email,
                context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
            ),
        )

        return ResponseEntity
            .created(URI.create("/api/v1/requests/${result.ticketNumber}"))
            .cacheControl(CacheControl.noStore())
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
        @PathVariable @Positive ticketNumber: Long,
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

@Schema(description = "고객 문의 접수 요청")
internal data class SubmitRequestBody(
    @field:Schema(description = "문의하는 고객의 표시 이름", example = "김고객")
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,

    @field:Schema(description = "고객 연락처로 사용할 이메일", example = "customer@example.com")
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,

    @field:Schema(description = "고객 문의 제목", example = "결제가 중복으로 처리됐어요")
    @field:NotBlank
    @field:Size(max = 200)
    val subject: String,

    @field:Schema(
        description = "첫 PUBLIC 코멘트로 저장되는 문의 본문",
        example = "주문 ORD-2026-1042의 결제가 두 번 승인되어 확인이 필요합니다.",
    )
    @field:NotBlank
    @field:Size(max = 20_000)
    val message: String,

    @field:Schema(description = "개인정보 처리 동의 여부", example = "true", nullable = true)
    val privacyConsent: Boolean? = null,
)

@Schema(description = "고객 문의 접수 결과")
internal data class SubmittedRequestResponse(
    @field:Schema(description = "사람이 식별하는 티켓 번호", example = "1042")
    val ticketNumber: Long,
    val status: CustomerRequestStatus,
    @field:Schema(description = "다시 조회할 때 사용하는 일회 발급 토큰", example = "example-token-not-valid-0000000000000000")
    val accessToken: String,
    val createdAt: Instant,
)

@Schema(description = "고객에게 공개 가능한 문의 상세")
internal data class PublicRequestResponse(
    val ticketNumber: Long,
    val subject: String,
    val status: CustomerRequestStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val comments: List<PublicCommentResponse>,
)

@Schema(description = "고객에게 노출할 수 있는 PUBLIC 코멘트")
internal data class PublicCommentResponse(
    val id: UUID,
    val authorDisplayName: String,
    val body: String,
    val createdAt: Instant,
)
