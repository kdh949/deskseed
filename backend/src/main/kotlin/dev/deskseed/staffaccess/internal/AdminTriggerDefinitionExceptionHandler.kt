package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.trigger.TriggerAuditUnavailableException
import dev.deskseed.trigger.TriggerConflictException
import dev.deskseed.trigger.TriggerNotFoundException
import dev.deskseed.trigger.TriggerPreconditionFailedException
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

@RestControllerAdvice(assignableTypes = [AdminTriggerDefinitionController::class])
internal class AdminTriggerDefinitionExceptionHandler {
    @ExceptionHandler(TriggerNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(request, HttpStatus.NOT_FOUND, "trigger-not-found", "Trigger not found")

    @ExceptionHandler(TriggerConflictException::class)
    fun conflict(exception: TriggerConflictException, request: HttpServletRequest) =
        problem(request, HttpStatus.CONFLICT, "trigger-conflict", "Trigger change conflicted")
            .also { it.body?.setProperty("code", exception.code) }

    @ExceptionHandler(TriggerPreconditionFailedException::class)
    fun precondition(exception: TriggerPreconditionFailedException, request: HttpServletRequest) =
        problem(request, HttpStatus.PRECONDITION_FAILED, "trigger-precondition-failed", "Trigger changed after the supplied ETag")
            .also { it.body?.setProperty("currentVersion", exception.currentVersion) }

    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(request, HttpStatus.BAD_REQUEST, "trigger-validation", "Trigger input is invalid")

    @ExceptionHandler(TriggerAuditUnavailableException::class)
    fun unavailable(request: HttpServletRequest) =
        problem(request, HttpStatus.SERVICE_UNAVAILABLE, "trigger-audit-unavailable", "Trigger change could not commit with its audit")

    private fun problem(request: HttpServletRequest, status: HttpStatus, code: String, detail: String): ResponseEntity<ProblemDetail> {
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("/problems/$code")
            title = detail
            instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
