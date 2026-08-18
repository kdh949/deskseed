package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.integration.IntegrationClientNotFoundException
import dev.deskseed.integration.IntegrationClientRatePolicyConflictException
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

@RestControllerAdvice(assignableTypes = [AdminIntegrationClientRatePolicyController::class])
internal class AdminIntegrationClientRatePolicyExceptionHandler {
    @ExceptionHandler(IntegrationClientRatePolicyConflictException::class)
    fun stale(
        exception: IntegrationClientRatePolicyConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = response(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "/problems/integration-client-rate-policy-version-mismatch",
        "Integration client rate policy changed",
        "Refresh the current policy before changing it.",
        "INTEGRATION_CLIENT_RATE_POLICY_VERSION_MISMATCH",
        mapOf("ETag" to "\"integration-client-v${exception.currentVersion}\""),
    )

    @ExceptionHandler(IntegrationClientNotFoundException::class)
    fun notFound(request: HttpServletRequest) = response(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/integration-client-not-found",
        "Integration client not found",
        "The requested integration client was not found.",
    )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
    )
    fun invalid(request: HttpServletRequest) = response(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "Integration client rate policy request validation failed",
        "The request does not satisfy the integration client rate policy contract.",
    )

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/admin-audit-unavailable",
        "Integration client rate policy unavailable",
        "The change could not be safely persisted and audited. Try again later.",
    )

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
        code: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            code?.let { setProperty("code", it) }
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        val response = ResponseEntity.status(status).header("Cache-Control", "no-store")
        headers.forEach(response::header)
        return response.body(problem)
    }
}
