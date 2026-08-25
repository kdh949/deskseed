package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.macro.MacroAuditUnavailableException
import dev.deskseed.macro.MacroConflictException
import dev.deskseed.macro.MacroNotFoundException
import dev.deskseed.macro.MacroPreconditionFailedException
import dev.deskseed.macro.MacroValidationException
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

@RestControllerAdvice(
    assignableTypes = [
        AgentMacroDefinitionController::class,
        AdminSharedMacroDefinitionController::class,
    ],
)
internal class MacroDefinitionExceptionHandler {
    @ExceptionHandler(MacroNotFoundException::class)
    fun notFound(request: HttpServletRequest) = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/macro-not-found",
        "Macro not found",
        "The requested macro was not found.",
    )

    @ExceptionHandler(MacroConflictException::class)
    fun conflict(exception: MacroConflictException, request: HttpServletRequest) = problem(
        request,
        HttpStatus.CONFLICT,
        "/problems/macro-conflict",
        "Macro change conflicted",
        "The requested macro change conflicts with current data.",
    ).also { it.body?.setProperty("code", exception.code) }

    @ExceptionHandler(MacroPreconditionFailedException::class)
    fun precondition(exception: MacroPreconditionFailedException, request: HttpServletRequest) = problem(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "/problems/macro-version-precondition-failed",
        "Macro version precondition failed",
        "The macro changed after the supplied ETag was read.",
    ).also { it.body?.setProperty("currentVersion", exception.currentVersion) }

    @ExceptionHandler(
        MacroValidationException::class,
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalid(request: HttpServletRequest) = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/macro-validation",
        "Macro validation failed",
        "One or more macro fields are invalid.",
    )

    @ExceptionHandler(MacroAuditUnavailableException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/macro-audit-unavailable",
        "Macro command unavailable",
        "The change could not be committed with its required audit.",
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
