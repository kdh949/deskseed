package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.outboundmail.OutboundMailIntentNotFoundException
import dev.deskseed.outboundmail.OutboundMailRetryInvalidException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminOutboundMailController::class])
internal class AdminOutboundMailExceptionHandler {
    @ExceptionHandler(OutboundMailIntentNotFoundException::class)
    fun notFound(request: HttpServletRequest) = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/outbound-mail-intent-not-found",
        "Outbound mail intent not found",
        "The requested outbound mail intent was not found.",
    )

    @ExceptionHandler(OutboundMailRetryInvalidException::class)
    fun retryConflict(request: HttpServletRequest) = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/outbound-mail-retry-conflict",
        "Outbound mail retry conflicted",
        "Only a terminal failed outbound mail intent can be retried.",
        "OUTBOUND_MAIL_RETRY_NOT_ALLOWED",
    )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        IllegalArgumentException::class,
    )
    fun invalid(request: HttpServletRequest) = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Outbound mail operation request validation failed",
        "One or more request fields are invalid.",
    )

    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun unavailable(request: HttpServletRequest) = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/outbound-mail-operations-unavailable",
        "Outbound mail operations unavailable",
        "The operation could not be safely persisted and audited. Try again later.",
    )

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        code: String? = null,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            code?.let { setProperty("code", it) }
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).body(problem)
    }
}
