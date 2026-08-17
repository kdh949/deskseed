package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.ticketconfiguration.TicketConfigurationAuditUnavailableException
import dev.deskseed.ticketconfiguration.TicketConfigurationConflictException
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketconfiguration.TicketConfigurationPreconditionFailedException
import dev.deskseed.ticketconfiguration.TicketConfigurationValidationException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminTicketConfigurationController::class])
internal class TicketConfigurationExceptionHandler {
    @ExceptionHandler(TicketConfigurationNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request, HttpStatus.NOT_FOUND, "/problems/ticket-configuration-not-found",
        "Ticket configuration not found", "The requested ticket configuration resource was not found.",
    )

    @ExceptionHandler(TicketConfigurationConflictException::class)
    fun conflict(exception: TicketConfigurationConflictException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(
            request, HttpStatus.CONFLICT, "/problems/ticket-configuration-conflict",
            "Ticket configuration change conflicted", "The requested configuration change conflicts with current data.",
        ).also { it.body?.setProperty("code", exception.code) }

    @ExceptionHandler(TicketConfigurationPreconditionFailedException::class)
    fun precondition(
        exception: TicketConfigurationPreconditionFailedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = problem(
        request, HttpStatus.PRECONDITION_FAILED, "/problems/ticket-configuration-version-precondition-failed",
        "Ticket configuration version precondition failed", "The configuration changed after the supplied ETag was read.",
    ).also { it.body?.setProperty("currentVersion", exception.currentVersion) }

    @ExceptionHandler(
        TicketConfigurationValidationException::class,
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request, HttpStatus.BAD_REQUEST, "/problems/ticket-configuration-validation",
        "Ticket configuration validation failed", "One or more configuration fields are invalid.",
    )

    @ExceptionHandler(TicketConfigurationAuditUnavailableException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/ticket-configuration-audit-unavailable",
        "Ticket configuration command unavailable", "The change could not be committed with its required audit.",
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
            this.instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
