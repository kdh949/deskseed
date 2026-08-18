package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjection
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjectionQuery
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketing.TicketKind
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import jakarta.validation.constraints.NotNull
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.util.UUID

/** The customer HTTP adapter belongs to ticketconfiguration to avoid a customer-to-ticketconfiguration module edge. */
@RestController
@RequestMapping("/api/v1/customer")
@Validated
internal class CustomerTicketFormController(
    private val formProjectionQuery: CustomerTicketFormProjectionQuery,
) {
    @GetMapping("/ticket-forms")
    fun form(
        @RequestParam(required = false) formId: UUID?,
        @RequestParam @NotNull ticketKind: TicketKind,
    ): ResponseEntity<CustomerTicketFormProjection> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(formProjectionQuery.project(formId, ticketKind))
}

@RestControllerAdvice(assignableTypes = [CustomerTicketFormController::class])
internal class CustomerTicketFormExceptionHandler {
    @ExceptionHandler(TicketConfigurationNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request, HttpStatus.NOT_FOUND, "/problems/customer-ticket-form-not-found",
        "Customer ticket form not found", "The requested customer ticket form is not available.",
    )

    @ExceptionHandler(IllegalArgumentException::class, ConstraintViolationException::class)
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request, HttpStatus.BAD_REQUEST, "/problems/validation",
        "Customer ticket form request is invalid", "One or more customer form parameters are invalid.",
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
