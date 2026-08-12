package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.beans.TypeMismatchException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AgentTicketReadController::class])
internal class AgentTicketReadExceptionHandler {
    @ExceptionHandler(AgentTicketNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/agent-ticket-not-found",
        "Ticket not found",
        "The requested ticket or view was not found.",
    )

    @ExceptionHandler(AccessAuditUnavailableException::class)
    fun auditUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/audit-write-unavailable",
        "Protected data unavailable",
        "The protected read or search could not be safely audited. Try again later.",
    )

    @ExceptionHandler(InvalidSearchOriginException::class)
    fun invalidSearchOrigin(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/invalid-search-origin",
        "Invalid search origin",
        "The search-result navigation context is invalid or no longer belongs to this session.",
    )

    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        TypeMismatchException::class,
        MissingRequestHeaderException::class,
    )
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Agent read request validation failed",
        "One or more request fields are invalid.",
    )

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem)
    }
}
