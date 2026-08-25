package dev.deskseed.portal.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.knowledge.KnowledgeNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [HelpCenterController::class])
internal class HelpCenterExceptionHandler {
    @ExceptionHandler(KnowledgeNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.NOT_FOUND,
        "/problems/knowledge-not-found",
        "Knowledge article not found",
        "The requested Help Center resource is not available.",
    )

    @ExceptionHandler(IllegalArgumentException::class, ConstraintViolationException::class, MethodArgumentNotValidException::class)
    fun invalid(request: HttpServletRequest): ResponseEntity<ProblemDetail> = problem(
        request,
        HttpStatus.BAD_REQUEST,
        "/problems/knowledge-validation",
        "Knowledge request validation failed",
        "One or more Help Center request fields are invalid.",
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
