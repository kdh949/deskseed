package dev.deskseed.staffaccess.internal

import dev.deskseed.aiassistance.AiAuditUnavailableException
import dev.deskseed.aiassistance.AiFeatureDisabledException
import dev.deskseed.aiassistance.AiPublicContextUnavailableException
import dev.deskseed.aiassistance.AiRequestConflictException
import dev.deskseed.aiassistance.AiRequestInvalidException
import dev.deskseed.aiassistance.AiRequestNotFoundException
import dev.deskseed.aiassistance.AiRequestRateLimitedException
import dev.deskseed.aiassistance.AiStatusUnavailableException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AgentAiRequestController::class])
internal class AgentAiRequestExceptionHandler {
    @ExceptionHandler(AiStatusUnavailableException::class)
    fun statusUnavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/ai-status-unavailable", "AI status unavailable",
        "The accepted AI job status is temporarily unavailable. Retry this read without creating a new job.",
    )

    @ExceptionHandler(AiFeatureDisabledException::class)
    fun disabled(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/ai-feature-disabled", "AI feature disabled",
        "This AI feature is not enabled for the current staff actor.",
    )

    @ExceptionHandler(AiRequestNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request, HttpStatus.NOT_FOUND, "/problems/ai-request-not-found", "AI request not found",
        "The AI request or its authorized ticket is not available.",
    )

    @ExceptionHandler(AiRequestConflictException::class)
    fun conflict(request: HttpServletRequest) = problem(
        request, HttpStatus.CONFLICT, "/problems/ai-request-conflict", "AI request conflict",
        "The idempotency key or current AI request state conflicts with this request.",
    )

    @ExceptionHandler(AiPublicContextUnavailableException::class)
    fun noPublicContext(request: HttpServletRequest) = problem(
        request, HttpStatus.UNPROCESSABLE_CONTENT, "/problems/ai-public-context-unavailable",
        "PUBLIC ticket context unavailable", "The ticket has no authorized PUBLIC conversation to process.",
    )

    @ExceptionHandler(AiRequestRateLimitedException::class)
    fun rateLimited(exception: AiRequestRateLimitedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> =
        problem(
            request, HttpStatus.TOO_MANY_REQUESTS, "/problems/ai-request-rate-limited",
            "AI request rate limit exceeded", "Retry after the current admission window resets.",
        ).also { it.headers.set("Retry-After", exception.retryAfterSeconds.toString()) }

    @ExceptionHandler(
        AiRequestInvalidException::class,
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        MissingRequestHeaderException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request, HttpStatus.BAD_REQUEST, "/problems/ai-request-invalid", "AI request invalid",
        "The AI request does not satisfy the frozen contract.",
    )

    @ExceptionHandler(AiAuditUnavailableException::class, DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/ai-request-unavailable", "AI request unavailable",
        "Required persistence or access auditing could not be completed.",
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
            setProperty("requestId", request.getAttribute(dev.deskseed.foundation.RequestIdFilter.REQUEST_ID_ATTRIBUTE))
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
