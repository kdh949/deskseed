package dev.deskseed.customerconsent.internal

import dev.deskseed.customerconsent.CanonicalCustomerConsentDocumentCodec
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicies
import dev.deskseed.customerconsent.CurrentCustomerConsentPolicy
import dev.deskseed.customerconsent.CustomerConsentContext
import dev.deskseed.customerconsent.CustomerConsentPolicyProjection
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customer/consent-policies")
@Validated
internal class CustomerConsentProjectionController(
    private val projection: CustomerConsentPolicyProjection,
) {
    private val documents = CanonicalCustomerConsentDocumentCodec()

    @GetMapping
    fun current(
        @RequestParam context: CustomerConsentContext,
    ): ResponseEntity<CurrentCustomerConsentPoliciesResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(projection.current(context).toResponse(documents))
}

@RestControllerAdvice(assignableTypes = [CustomerConsentProjectionController::class])
internal class CustomerConsentProjectionExceptionHandler {
    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        ServletRequestBindingException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/customer-consent-request-invalid",
        "고객 동의 정책 요청을 처리할 수 없습니다",
        "요청한 고객 동의 정책 context가 올바르지 않습니다.",
    )

    @ExceptionHandler(CustomerConsentUnavailableException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/customer-consent-unavailable",
        "고객 동의 정책 요청을 안전하게 완료할 수 없습니다",
        "현재 고객 동의 정책을 안전하게 조회할 수 없습니다.",
    )

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .body(ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        })
}

internal data class CurrentCustomerConsentPoliciesResponse(
    val context: CustomerConsentContext,
    val policies: List<CurrentCustomerConsentPolicyResponse>,
)

internal data class CurrentCustomerConsentPolicyResponse(
    val policyId: UUID,
    val policyKey: String,
    val version: Int,
    val title: String,
    val document: Map<String, Any>,
    val checksumSha256: String,
    val required: Boolean,
    val displayOrder: Int,
    val effectiveAt: Instant,
)

private fun CurrentCustomerConsentPolicies.toResponse(
    documents: CanonicalCustomerConsentDocumentCodec,
) = CurrentCustomerConsentPoliciesResponse(
    context,
    policies.map { it.toResponse(documents) },
)

private fun CurrentCustomerConsentPolicy.toResponse(
    documents: CanonicalCustomerConsentDocumentCodec,
) = CurrentCustomerConsentPolicyResponse(
    policyId,
    policyKey,
    version,
    title,
    documents.encode(document),
    checksumSha256,
    required,
    displayOrder,
    effectiveAt,
)
