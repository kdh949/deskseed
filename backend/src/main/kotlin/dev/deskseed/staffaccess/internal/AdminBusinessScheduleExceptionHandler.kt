package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.sla.BusinessScheduleConflictException
import dev.deskseed.sla.BusinessScheduleNotFoundException
import dev.deskseed.sla.BusinessSchedulePreconditionFailedException
import dev.deskseed.sla.BusinessScheduleValidationException
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

@RestControllerAdvice(assignableTypes = [AdminBusinessScheduleController::class])
internal class AdminBusinessScheduleExceptionHandler {
    @ExceptionHandler(BusinessScheduleValidationException::class)
    fun validation(
        exception: BusinessScheduleValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/business-schedule-validation",
        "Business schedule validation failed",
        "One or more schedule fields are invalid.",
    ) {
        setProperty("fieldErrors", exception.issues)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/business-schedule-validation",
        "Business schedule validation failed",
        "One or more schedule fields are invalid.",
    ) {
        setProperty(
            "fieldErrors",
            exception.bindingResult.fieldErrors.map { field ->
                mapOf(
                    "field" to field.field,
                    "code" to (field.code ?: "INVALID"),
                    "message" to (field.defaultMessage ?: "Invalid value."),
                )
            },
        )
    }

    @ExceptionHandler(ConstraintViolationException::class, HttpMessageNotReadableException::class, IllegalArgumentException::class)
    fun badRequest(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/business-schedule-validation",
        "Business schedule validation failed",
        "The schedule request is invalid.",
    )

    @ExceptionHandler(BusinessScheduleNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/business-schedule-not-found",
        "Business schedule not found",
        "The requested business schedule was not found.",
    )

    @ExceptionHandler(BusinessScheduleConflictException::class)
    fun conflict(
        exception: BusinessScheduleConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.CONFLICT,
        "/problems/business-schedule-conflict",
        "Business schedule change conflicted",
        "The requested schedule change conflicts with an existing resource.",
    ) {
        setProperty("code", exception.code)
    }

    @ExceptionHandler(BusinessSchedulePreconditionFailedException::class)
    fun precondition(
        exception: BusinessSchedulePreconditionFailedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "/problems/business-schedule-version-conflict",
        "Business schedule changed",
        "Reload the schedule before saving or activating a version.",
    ) {
        setProperty("currentAggregateVersion", exception.currentAggregateVersion)
    }

    @ExceptionHandler(DataAccessException::class)
    fun auditUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/admin-audit-unavailable",
        "Business schedule change unavailable",
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
