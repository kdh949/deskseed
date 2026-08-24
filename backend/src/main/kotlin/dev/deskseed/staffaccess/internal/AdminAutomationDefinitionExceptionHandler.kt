package dev.deskseed.staffaccess.internal

import dev.deskseed.automation.AutomationAuditUnavailableException
import dev.deskseed.automation.AutomationConflictException
import dev.deskseed.automation.AutomationNotFoundException
import dev.deskseed.automation.AutomationPreconditionFailedException
import dev.deskseed.foundation.RequestIdFilter
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

@RestControllerAdvice(assignableTypes = [AdminAutomationDefinitionController::class])
internal class AdminAutomationDefinitionExceptionHandler {
    @ExceptionHandler(AutomationNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(request, HttpStatus.NOT_FOUND, "automation-not-found")
    @ExceptionHandler(AutomationConflictException::class)
    fun conflict(exception: AutomationConflictException, request: HttpServletRequest) =
        problem(request, HttpStatus.CONFLICT, "automation-conflict").also { it.body?.setProperty("code", exception.code) }
    @ExceptionHandler(AutomationPreconditionFailedException::class)
    fun precondition(exception: AutomationPreconditionFailedException, request: HttpServletRequest) =
        problem(request, HttpStatus.PRECONDITION_FAILED, "automation-precondition-failed")
            .also { it.body?.setProperty("currentVersion", exception.currentVersion) }
    @ExceptionHandler(IllegalArgumentException::class, ConstraintViolationException::class, MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun invalid(request: HttpServletRequest) = problem(request, HttpStatus.BAD_REQUEST, "automation-validation")
    @ExceptionHandler(AutomationAuditUnavailableException::class)
    fun unavailable(request: HttpServletRequest) = problem(request, HttpStatus.SERVICE_UNAVAILABLE, "automation-audit-unavailable")

    private fun problem(request: HttpServletRequest, status: HttpStatus, code: String): ResponseEntity<ProblemDetail> {
        val detail = "Automation request could not be completed"
        val body = ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("/problems/$code")
            title = detail
            instance = URI.create(request.requestURI)
            setProperty("requestId", request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString())
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
