package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.integration.ExternalReferenceValidationException
import dev.deskseed.integration.ExternalSystemConflictException
import dev.deskseed.integration.ExternalSystemNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminExternalSystemController::class])
internal class AdminExternalSystemExceptionHandler {
    @ExceptionHandler(ExternalSystemConflictException::class)
    fun conflict(
        exception: ExternalSystemConflictException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val status = if (exception.code == "EXTERNAL_SYSTEM_VERSION_STALE") {
            HttpStatus.PRECONDITION_FAILED
        } else {
            HttpStatus.CONFLICT
        }
        val response = problem(
            request,
            status,
            if (status == HttpStatus.PRECONDITION_FAILED) {
                "/problems/external-system-version-precondition-failed"
            } else {
                "/problems/external-system-conflict"
            },
            "External system change conflicted",
            "The requested external system change cannot be applied.",
        )
        response.body?.apply {
            setProperty("code", exception.code)
            exception.currentVersion?.let { setProperty("currentVersion", it) }
        }
        return response
    }

    @ExceptionHandler(ExternalSystemNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/external-system-not-found",
        "External system not found",
        "The requested external system was not found.",
    )

    @ExceptionHandler(
        ExternalReferenceValidationException::class,
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/validation",
        "External system request validation failed",
        "One or more external system fields are invalid.",
    )

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/admin-audit-unavailable",
        "External system change unavailable",
        "The change could not be safely persisted and audited. Try again later.",
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
