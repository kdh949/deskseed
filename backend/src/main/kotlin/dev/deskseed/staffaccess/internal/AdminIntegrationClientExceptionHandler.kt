package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.integration.IntegrationClientConflictException
import dev.deskseed.integration.IntegrationClientNotFoundException
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

@RestControllerAdvice(assignableTypes = [AdminIntegrationClientController::class])
internal class AdminIntegrationClientExceptionHandler {
    @ExceptionHandler(IntegrationClientConflictException::class)
    fun conflict(
        exception: IntegrationClientConflictException,
        request: HttpServletRequest,
    ) = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/integration-client-conflict",
        "Integration client change conflicted",
        "The requested integration client change cannot be applied.",
        exception.code,
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
        "Integration client request validation failed",
        "One or more request fields are invalid.",
    )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun dataConflict(exception: DataIntegrityViolationException, request: HttpServletRequest) = response(
        request,
        HttpStatus.CONFLICT,
        "/problems/integration-client-conflict",
        "Integration client change conflicted",
        "The requested integration client change conflicts with an existing resource.",
        if (exception.hasConstraint("integration_clients_name_ci_unique")) {
            "DUPLICATE_INTEGRATION_CLIENT_NAME"
        } else {
            "RESOURCE_CONFLICT"
        },
    )

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = response(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/admin-audit-unavailable",
        "Integration client change unavailable",
        "The change could not be safely persisted and audited. Try again later.",
    )

    private fun Throwable.hasConstraint(constraint: String): Boolean =
        generateSequence(this) { it.cause }.any { it.message?.contains(constraint) == true }

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
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).body(problem)
    }
}
