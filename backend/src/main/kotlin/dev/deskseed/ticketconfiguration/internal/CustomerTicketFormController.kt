package dev.deskseed.ticketconfiguration.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjection
import dev.deskseed.ticketconfiguration.CustomerTicketFormProjectionQuery
import dev.deskseed.ticketconfiguration.TicketConfigurationNotFoundException
import dev.deskseed.ticketing.TicketKind
import dev.deskseed.ticketing.CustomerRequestFormValues
import dev.deskseed.ticketing.TicketConfigurationFieldValue
import dev.deskseed.ticketing.CustomerFormUnavailableException
import dev.deskseed.ticketing.CustomerFormValidationException
import dev.deskseed.ticketing.CustomerFormVersionConflictException
import dev.deskseed.ticketing.CustomerRequestConfigurationConflictException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import java.math.BigDecimal
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
    @PostMapping("/ticket-form-projections")
    fun candidate(@Valid @RequestBody body: CustomerTicketFormCandidateRequest): ResponseEntity<CustomerTicketFormProjection> {
        if (body.ticketKind != "CUSTOMER_REQUEST") throw CustomerFormValidationException()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(formProjectionQuery.projectCandidate(
            CustomerRequestFormValues(body.formId, body.formVersion, body.fieldValues.mapValues { it.value.command() }),
        ))
    }

    @GetMapping("/ticket-forms")
    fun form(
        @RequestParam(required = false) formId: UUID?,
        request: HttpServletRequest,
    ): ResponseEntity<CustomerTicketFormProjection> {
        if (request.parameterMap.keys.any { it != "formId" }) throw CustomerFormValidationException()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(formProjectionQuery.project(formId, TicketKind.CUSTOMER_REQUEST))
    }
}

@RestControllerAdvice(assignableTypes = [CustomerTicketFormController::class])
internal class CustomerTicketFormExceptionHandler {
    @ExceptionHandler(TicketConfigurationNotFoundException::class, CustomerFormUnavailableException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request, HttpStatus.NOT_FOUND, "/problems/customer-ticket-form-unavailable",
        "Customer ticket form not found", "The requested customer ticket form is not available.",
    )

    @ExceptionHandler(CustomerFormVersionConflictException::class, CustomerRequestConfigurationConflictException::class)
    fun stale(request: HttpServletRequest) = problem(request, HttpStatus.CONFLICT,
        "/problems/customer-ticket-form-version-conflict", "Customer form changed", "Refresh the customer form before submitting.")

    @ExceptionHandler(CustomerFormValidationException::class)
    fun fieldsInvalid(request: HttpServletRequest) = problem(request, HttpStatus.BAD_REQUEST,
        "/problems/customer-ticket-form-validation-failed", "Customer form values are invalid", "Check the customer form values.")

    @ExceptionHandler(IllegalArgumentException::class, ConstraintViolationException::class,
        org.springframework.web.bind.MethodArgumentNotValidException::class,
        org.springframework.web.bind.ServletRequestBindingException::class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class,
        org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request, HttpStatus.BAD_REQUEST, "/problems/customer-ticket-form-validation-failed",
        "Customer ticket form request is invalid", "One or more customer form parameters are invalid.",
    )

    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = problem(request, HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/customer-request-configuration-unavailable", "Customer form unavailable", "The customer form cannot be read safely.")

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

internal data class CustomerTicketFormCandidateRequest(
    val ticketKind: String,
    val formId: UUID,
    val formVersion: Int,
    @field:Size(max = 100) @field:Valid val fieldValues: Map<String, CustomerTicketFieldCandidateValue>,
)
internal data class CustomerTicketFieldCandidateValue(
    val booleanValue: Boolean? = null,
    val numberValue: BigDecimal? = null,
    val optionId: UUID? = null,
    @field:Size(max = 1000) val shortTextValue: String? = null,
    @field:Size(max = 10000) val longTextValue: String? = null,
) {
    fun command() = TicketConfigurationFieldValue(booleanValue, numberValue?.toString(), optionId, shortTextValue, longTextValue)
}
