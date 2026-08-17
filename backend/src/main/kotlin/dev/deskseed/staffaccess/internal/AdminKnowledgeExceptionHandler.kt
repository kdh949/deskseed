package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.knowledge.KnowledgeConflictException
import dev.deskseed.knowledge.KnowledgeNotFoundException
import dev.deskseed.knowledge.KnowledgePreconditionFailedException
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

@RestControllerAdvice(assignableTypes = [AdminKnowledgeController::class])
internal class AdminKnowledgeExceptionHandler {
    @ExceptionHandler(KnowledgeNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/knowledge-not-found",
        "Knowledge resource not found",
        "The requested knowledge resource was not found.",
    )

    @ExceptionHandler(KnowledgeConflictException::class, DataIntegrityViolationException::class)
    fun conflict(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.CONFLICT,
        "/problems/knowledge-conflict",
        "Knowledge change conflicted",
        "The requested knowledge change conflicts with the current hierarchy.",
    )

    @ExceptionHandler(KnowledgePreconditionFailedException::class)
    fun precondition(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.PRECONDITION_FAILED,
        "/problems/knowledge-version-conflict",
        "Knowledge resource changed",
        "Reload the knowledge resource before saving it again.",
    )

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
        IllegalArgumentException::class,
    )
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/knowledge-validation",
        "Knowledge request validation failed",
        "One or more knowledge request fields are invalid.",
    )

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "/problems/knowledge-audit-unavailable",
        "Knowledge change unavailable",
        "The change could not be safely audited or queued. Try again later.",
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
        return ResponseEntity.status(status).body(body)
    }
}
