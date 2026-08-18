package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.webhook.WebhookConflictException
import dev.deskseed.webhook.WebhookDeliveryNotFoundException
import dev.deskseed.webhook.WebhookEndpointNotFoundException
import dev.deskseed.webhook.WebhookTargetRejectedException
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

@RestControllerAdvice(assignableTypes = [AdminWebhookController::class])
internal class AdminWebhookExceptionHandler {
    @ExceptionHandler(WebhookEndpointNotFoundException::class, WebhookDeliveryNotFoundException::class)
    fun notFound(request: HttpServletRequest) = response(request, HttpStatus.NOT_FOUND, "/problems/webhook-not-found", "Webhook resource not found", "The requested webhook resource was not found.")

    @ExceptionHandler(WebhookConflictException::class)
    fun conflict(exception: WebhookConflictException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val status = if (exception.currentVersion != null) HttpStatus.PRECONDITION_FAILED else HttpStatus.CONFLICT
        val result = response(request, status, "/problems/webhook-conflict", "Webhook change conflicted", "The requested webhook change cannot be applied.", exception.code)
        return if (exception.currentVersion == null) result else ResponseEntity.status(result.statusCode)
            .headers(result.headers)
            .header("ETag", "\"webhook-v${exception.currentVersion}\"")
            .body(result.body)
    }

    @ExceptionHandler(
        WebhookTargetRejectedException::class,
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
    )
    fun invalid(request: HttpServletRequest) = response(request, HttpStatus.BAD_REQUEST, "/problems/webhook-validation", "Webhook request validation failed", "One or more webhook request fields are invalid.")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(request: HttpServletRequest) = response(request, HttpStatus.CONFLICT, "/problems/webhook-conflict", "Webhook change conflicted", "The requested webhook change conflicts with an existing resource.")

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = response(request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/admin-audit-unavailable", "Webhook change unavailable", "The change could not be safely persisted and audited. Try again later.")

    private fun response(request: HttpServletRequest, status: HttpStatus, type: String, title: String, detail: String, code: String? = null): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            code?.let { setProperty("code", it) }
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(problem)
    }
}
