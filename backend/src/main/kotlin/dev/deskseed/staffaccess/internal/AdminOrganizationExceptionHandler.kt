package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.organization.OrganizationConflictException
import dev.deskseed.organization.OrganizationNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminOrganizationController::class])
internal class AdminOrganizationExceptionHandler {
    @ExceptionHandler(OrganizationConflictException::class)
    fun conflict(
        exception: OrganizationConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/admin-organization-conflict",
        "Organization change conflicted",
        "The requested organization change cannot be applied.",
        exception.code,
    )

    @ExceptionHandler(OrganizationNotFoundException::class)
    fun notFound(
        exception: OrganizationNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/admin-organization-not-found",
        "Organization resource not found",
        "The requested organization resource was not found.",
        exception.code,
    )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
    )
    fun invalidRequest(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Organization request validation failed",
        "One or more request fields are invalid.",
    )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun uniquenessConflict(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/admin-organization-conflict",
        "Organization change conflicted",
        "The requested organization change conflicts with an existing resource.",
        "RESOURCE_CONFLICT",
    )

    @ExceptionHandler(DataAccessException::class)
    fun auditUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/admin-audit-unavailable",
        "Organization change unavailable",
        "The change could not be safely audited. Try again later.",
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
            setProperty(
                "requestId",
                request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString(),
            )
        }
        return ResponseEntity.status(status).body(problem)
    }
}
