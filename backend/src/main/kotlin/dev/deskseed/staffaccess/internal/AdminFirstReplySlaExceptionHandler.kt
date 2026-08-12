package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.sla.FirstReplySlaPolicyConflictException
import dev.deskseed.sla.FirstReplySlaPolicyNotFoundException
import dev.deskseed.sla.FirstReplySlaPolicyPreconditionFailedException
import dev.deskseed.sla.FirstReplySlaPolicyValidationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminFirstReplySlaController::class])
internal class AdminFirstReplySlaExceptionHandler {
    @ExceptionHandler(FirstReplySlaPolicyValidationException::class)
    fun validation(exception: FirstReplySlaPolicyValidationException, request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/sla-policy-validation",
        "SLA policy validation failed",
        exception.message ?: "The SLA policy is invalid.",
    ) { setProperty("fieldErrors", listOf(mapOf("field" to exception.field, "code" to exception.code))) }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidation(exception: MethodArgumentNotValidException, request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/sla-policy-validation",
        "SLA policy validation failed",
        "One or more policy fields are invalid.",
    ) {
        setProperty("fieldErrors", exception.bindingResult.fieldErrors.map {
            mapOf("field" to it.field, "code" to (it.code ?: "INVALID"), "message" to (it.defaultMessage ?: "Invalid"))
        })
    }

    @ExceptionHandler(ConstraintViolationException::class, HttpMessageNotReadableException::class, IllegalArgumentException::class)
    fun badRequest(request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/sla-policy-validation",
        "SLA policy validation failed",
        "The SLA policy request is invalid.",
    )

    @ExceptionHandler(FirstReplySlaPolicyNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/sla-policy-not-found",
        "SLA policy not found",
        "The requested SLA policy was not found.",
    )

    @ExceptionHandler(FirstReplySlaPolicyConflictException::class)
    fun conflict(exception: FirstReplySlaPolicyConflictException, request: HttpServletRequest) = problem(
        request,
        HttpStatus.CONFLICT,
        "/problems/sla-policy-conflict",
        "SLA policy change conflicted",
        "The policy references an unavailable resource.",
    ) { setProperty("code", exception.code) }

    @ExceptionHandler(FirstReplySlaPolicyPreconditionFailedException::class)
    fun precondition(exception: FirstReplySlaPolicyPreconditionFailedException, request: HttpServletRequest) = problem(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "/problems/sla-policy-version-conflict",
        "SLA policy changed",
        "Reload the policy before saving or activating a version.",
    ) { setProperty("currentAggregateVersion", exception.currentAggregateVersion) }

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/admin-audit-unavailable",
        "SLA policy change unavailable",
        "The change could not be safely audited. Try again later.",
    )

    private fun problem(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        customize: ProblemDetail.() -> Unit = {},
    ): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
            customize()
        }
        return ResponseEntity.status(status).body(body)
    }
}
