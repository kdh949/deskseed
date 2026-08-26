package dev.deskseed.staffaccess.internal

import dev.deskseed.customerconsent.CustomerConsentConflictException
import dev.deskseed.customerconsent.CustomerConsentNotFoundException
import dev.deskseed.customerconsent.CustomerConsentPreconditionFailedException
import dev.deskseed.customerconsent.CustomerConsentUnavailableException
import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.ServletRequestBindingException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminCustomerConsentController::class])
internal class AdminCustomerConsentExceptionHandler {
    @ExceptionHandler(CustomerConsentNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request, HttpStatus.NOT_FOUND, "/problems/customer-consent-not-found",
        "고객 동의 정책을 찾을 수 없습니다", "요청한 고객 동의 정책을 찾을 수 없습니다.",
    )

    @ExceptionHandler(CustomerConsentConflictException::class)
    fun conflict(request: HttpServletRequest) = problem(
        request, HttpStatus.CONFLICT, "/problems/customer-consent-conflict",
        "현재 고객 동의 정책 상태와 요청이 충돌합니다", "현재 정책 상태에서 요청한 변경을 적용할 수 없습니다.",
    )

    @ExceptionHandler(CustomerConsentPreconditionFailedException::class)
    fun precondition(
        exception: CustomerConsentPreconditionFailedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        request, HttpStatus.PRECONDITION_FAILED, "/problems/customer-consent-precondition-failed",
        "고객 동의 정책 버전이 변경되었습니다", "현재 버전을 새로 읽고 강한 ETag로 다시 시도해 주세요.",
    ).let { response ->
        response.body?.setProperty("currentVersion", exception.currentVersion)
        ResponseEntity.status(response.statusCode)
            .cacheControl(CacheControl.noStore())
            .eTag(exception.currentVersion.toString())
            .body(response.body)
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(request: HttpServletRequest) = problem(
        request, HttpStatus.FORBIDDEN, "/problems/customer-consent-forbidden",
        "고객 동의 정책 관리 권한이 없습니다", "이 고객 동의 정책을 관리할 권한이 없습니다.",
    )

    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        ServletRequestBindingException::class,
        MethodArgumentTypeMismatchException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request, HttpStatus.BAD_REQUEST, "/problems/customer-consent-request-invalid",
        "고객 동의 정책 요청을 처리할 수 없습니다", "요청 형식 또는 고객 동의 정책 입력이 유효하지 않습니다.",
    )

    @ExceptionHandler(CustomerConsentUnavailableException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/customer-consent-unavailable",
        "고객 동의 정책 요청을 안전하게 완료할 수 없습니다", "필수 저장 또는 감사와 함께 요청을 완료할 수 없습니다.",
    )

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
