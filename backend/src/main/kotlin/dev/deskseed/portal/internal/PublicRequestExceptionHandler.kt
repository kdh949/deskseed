package dev.deskseed.portal.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.attachments.AttachmentInfectedException
import dev.deskseed.attachments.AttachmentLinkInvalidException
import dev.deskseed.attachments.AttachmentMimeMismatchException
import dev.deskseed.attachments.AttachmentNotFoundException
import dev.deskseed.attachments.AttachmentTooLargeException
import dev.deskseed.attachments.AttachmentUnavailableException
import dev.deskseed.portal.RequestNotFoundException
import dev.deskseed.settings.AnonymousSubmissionDisabledException
import dev.deskseed.ticketing.CustomerCommandIdReusedException
import dev.deskseed.ticketing.CustomerFollowUpConflictException
import dev.deskseed.ticketing.CustomerTicketClaimDeniedException
import dev.deskseed.ticketing.CustomerTicketNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.dao.DataAccessException
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.net.URI

@RestControllerAdvice(assignableTypes = [PublicRequestController::class, CustomerRequestPortalController::class])
internal class PublicRequestExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = exception.bindingResult.fieldErrors.map {
            mapOf(
                "field" to it.field,
                "message" to (it.defaultMessage ?: "invalid value"),
                "code" to (it.code ?: "invalid"),
            )
        }

        val problem = problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Request validation failed",
            detail = "One or more request fields are invalid.",
            type = "/problems/validation",
            request = request,
        ).apply {
            setProperty("fieldErrors", errors)
        }

        return ResponseEntity.status(problem.status).body(problem)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Malformed request body",
            detail = "The JSON request body could not be read.",
            type = "/problems/malformed-json",
            request = request,
        ),
    )

    @ExceptionHandler(
        MissingRequestHeaderException::class,
        MethodArgumentTypeMismatchException::class,
        HandlerMethodValidationException::class,
        ConstraintViolationException::class,
    )
    fun handleRequestShape(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Request validation failed",
            detail = "One or more request fields are invalid.",
            type = "/problems/validation",
            request = request,
        ),
    )

    @ExceptionHandler(DataAccessException::class, TransactionException::class)
    fun handlePersistenceFailure(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            title = "Request storage unavailable",
            detail = "The request could not be processed safely.",
            type = "/problems/request-storage-unavailable",
            request = request,
        ),
    )

    @ExceptionHandler(PublicRequestRateLimitExceededException::class)
    fun handleRateLimitExceeded(
        exception: PublicRequestRateLimitExceededException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.TOO_MANY_REQUESTS,
            title = "Request rate limit exceeded",
            detail = "Retry after the current request rate-limit window resets.",
            type = "/problems/request-rate-limit-exceeded",
            request = request,
        ),
        headers = HttpHeaders().apply { set("Retry-After", exception.retryAfter.seconds.toString()) },
    )

    @ExceptionHandler(PublicRequestRateLimitUnavailableException::class)
    fun handleRateLimitUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            title = "Request rate limit unavailable",
            detail = "The request could not be processed safely.",
            type = "/problems/request-rate-limit-unavailable",
            request = request,
        ),
    )

    @ExceptionHandler(PublicRequestNetworkBoundaryException::class)
    fun handleInvalidRequestNetwork(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Request network invalid",
            detail = "The request network information is invalid.",
            type = "/problems/request-network-invalid",
            request = request,
        ),
    )

    @ExceptionHandler(AnonymousSubmissionDisabledException::class)
    fun handleAnonymousDisabled(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.FORBIDDEN,
            title = "Anonymous submission is disabled",
            detail = "This installation currently requires customer registration.",
            type = "/problems/anonymous-submission-disabled",
            request = request,
        ),
    )

    @ExceptionHandler(RequestNotFoundException::class)
    fun handleRequestNotFound(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.NOT_FOUND,
            title = "Request not found",
            detail = "The ticket number and access token do not identify a visible request.",
            type = "/problems/request-not-found",
            request = request,
        ),
    )

    @ExceptionHandler(AttachmentNotFoundException::class)
    fun handleAttachmentNotFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.NOT_FOUND,
            title = "Attachment not found",
            detail = "The requested attachment is not available.",
            type = "/problems/attachment-not-found",
            request = request,
        ),
    )

    @ExceptionHandler(AttachmentTooLargeException::class)
    fun handleAttachmentTooLarge(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment too large", "The upload exceeds the configured limit.", "/problems/attachment-too-large", request),
    )

    @ExceptionHandler(AttachmentMimeMismatchException::class)
    fun handleAttachmentMimeMismatch(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Attachment type rejected", "The declared and detected types are incompatible.", "/problems/attachment-mime-mismatch", request),
    )

    @ExceptionHandler(AttachmentInfectedException::class)
    fun handleAttachmentInfected(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "Attachment rejected", "The attachment did not pass the malware scan.", "/problems/attachment-infected", request),
    )

    @ExceptionHandler(AttachmentUnavailableException::class)
    fun handleAttachmentUnavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(HttpStatus.SERVICE_UNAVAILABLE, "Attachment service unavailable", "The attachment operation could not be completed safely.", "/problems/attachment-unavailable", request),
    )

    @ExceptionHandler(CustomerTicketNotFoundException::class)
    fun handleCustomerTicketNotFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.NOT_FOUND,
            title = "Request not found",
            detail = "The request is not available to this customer.",
            type = "/problems/customer-request-not-found",
            request = request,
        ),
    )

    @ExceptionHandler(CustomerTicketClaimDeniedException::class)
    fun handleCustomerClaimDenied(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.FORBIDDEN,
            title = "Request claim denied",
            detail = "The request cannot be linked with this proof and customer session.",
            type = "/problems/customer-request-claim-denied",
            request = request,
        ),
    )

    @ExceptionHandler(CustomerFollowUpConflictException::class, CustomerCommandIdReusedException::class)
    fun handleCustomerConflict(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.CONFLICT,
            title = "Customer request conflict",
            detail = "The request cannot accept this follow-up in its current state.",
            type = "/problems/customer-request-conflict",
            request = request,
        ),
    )

    @ExceptionHandler(IllegalArgumentException::class, AttachmentLinkInvalidException::class)
    fun handleIllegalArgument(request: HttpServletRequest): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Request validation failed",
            detail = "One or more request fields are invalid.",
            type = "/problems/validation",
            request = request,
        ),
    )

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
        type: String,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply {
        this.title = title
        this.type = URI.create(type)
        this.instance = URI.create(request.requestURI)
        setProperty(
            "requestId",
            request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString(),
        )
    }

    private fun respond(
        problem: ProblemDetail,
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<ProblemDetail> = ResponseEntity.status(problem.status).headers(headers).body(problem)
}
