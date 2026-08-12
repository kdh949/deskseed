package dev.deskseed.platformapi.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.integration.AuthenticatedIntegrationClient
import dev.deskseed.ticketing.PlatformTicketAuditUnavailableException
import dev.deskseed.ticketing.PlatformTicketInvalidException
import dev.deskseed.ticketing.PlatformTicketNotFoundException
import dev.deskseed.ticketing.PlatformTicketVersionException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException

@RestControllerAdvice(assignableTypes = [PlatformTicketController::class])
internal class PlatformTicketExceptionHandler(
    private val securityAuditRecorder: PlatformSecurityAuditRecorder,
) {
    @ExceptionHandler(PlatformTicketInvalidException::class)
    fun invalid(exception: PlatformTicketInvalidException, request: HttpServletRequest) = problem(
        400,
        "/problems/platform-request-invalid",
        "Invalid Platform API request",
        "The request does not satisfy the Platform API contract.",
        request,
        mapOf("code" to exception.code),
    )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
        MissingRequestHeaderException::class,
        PlatformIfMatchInvalidException::class,
    )
    fun malformed(exception: Exception, request: HttpServletRequest) = problem(
        400,
        "/problems/platform-request-invalid",
        "Invalid Platform API request",
        "The request does not satisfy the Platform API contract.",
        request,
    )

    @ExceptionHandler(PlatformScopeDeniedException::class, PlatformResourceDeniedException::class)
    fun forbidden(exception: RuntimeException, request: HttpServletRequest) = problem(
        403,
        "/problems/platform-access-denied",
        "Access denied",
        "The authenticated client is not allowed to perform this operation.",
        request,
    )

    @ExceptionHandler(PlatformTicketNotFoundException::class)
    fun notFound(exception: PlatformTicketNotFoundException, request: HttpServletRequest) = problem(
        404,
        "/problems/platform-ticket-not-found",
        "Ticket not found",
        "The requested Platform ticket was not found.",
        request,
    )

    @ExceptionHandler(PlatformIdempotencyKeyInvalidException::class)
    fun invalidKey(exception: PlatformIdempotencyKeyInvalidException, request: HttpServletRequest) = problem(
        400,
        "/problems/idempotency-key-invalid",
        "Invalid idempotency key",
        "Idempotency-Key must be a bounded printable value.",
        request,
    )

    @ExceptionHandler(PlatformIdempotencyKeyReusedException::class)
    fun reusedKey(
        exception: PlatformIdempotencyKeyReusedException,
        request: HttpServletRequest,
        authentication: Authentication,
    ): ResponseEntity<Map<String, Any?>> {
        val principal = authentication.principal as AuthenticatedIntegrationClient
        securityAuditRecorder.denied(
            principal,
            request.identifier(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
            request.identifier(RequestIdFilter.CORRELATION_ID_ATTRIBUTE),
            "IDEMPOTENCY_KEY_REUSED",
            request.requestURI.takeLast(80),
        )
        return problem(
            409,
            "/problems/idempotency-key-reused",
            "Idempotency key reused",
            "The idempotency key was already used with a different request.",
            request,
        )
    }

    @ExceptionHandler(PlatformIdempotencyInProgressException::class)
    fun inProgress(exception: PlatformIdempotencyInProgressException, request: HttpServletRequest): ResponseEntity<Map<String, Any?>> =
        problem(
            409,
            "/problems/idempotency-in-progress",
            "Request is in progress",
            "Retry the same request after the indicated delay.",
            request,
            headers = mapOf(HttpHeaders.RETRY_AFTER to "1"),
        )

    @ExceptionHandler(PlatformTicketVersionException::class)
    fun stale(exception: PlatformTicketVersionException, request: HttpServletRequest) = problem(
        412,
        "/problems/ticket-version-precondition-failed",
        "Ticket version precondition failed",
        "Refresh the ticket and retry with its current ETag.",
        request,
        mapOf("currentVersion" to exception.currentVersion, "currentETag" to "\"ticket-v${exception.currentVersion}\""),
        mapOf(HttpHeaders.ETAG to "\"ticket-v${exception.currentVersion}\""),
    )

    @ExceptionHandler(PlatformTicketAuditUnavailableException::class, DataAccessException::class)
    fun unavailable(exception: Exception, request: HttpServletRequest) = problem(
        503,
        "/problems/platform-audit-unavailable",
        "Platform operation unavailable",
        "Required persistence or audit could not be completed.",
        request,
    )

    private fun problem(
        status: Int,
        type: String,
        title: String,
        detail: String,
        request: HttpServletRequest,
        extensions: Map<String, Any?> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<Map<String, Any?>> {
        val body = linkedMapOf<String, Any?>(
            "type" to type,
            "title" to title,
            "status" to status,
            "detail" to detail,
            "requestId" to request.identifier(RequestIdFilter.REQUEST_ID_ATTRIBUTE),
        ) + extensions
        val response = ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store")
        headers.forEach(response::header)
        return response.body(body)
    }

    private fun HttpServletRequest.identifier(attribute: String): String = getAttribute(attribute)?.toString().orEmpty()
}
