package dev.deskseed.portal.internal

import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.portal.RequestNotFoundException
import dev.deskseed.settings.AnonymousSubmissionDisabledException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
internal class PublicRequestExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val errors = exception.bindingResult.fieldErrors
            .groupBy({ it.field }, { it.defaultMessage ?: "invalid value" })
            .mapValues { (_, messages) -> messages.joinToString("; ") }

        val problem = problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Request validation failed",
            detail = "One or more request fields are invalid.",
            type = "/problems/validation",
            request = request,
        ).apply {
            setProperty("errors", errors)
        }

        return ResponseEntity.status(problem.status).body(problem)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Malformed request body",
            detail = "The JSON request body could not be read.",
            type = "/problems/malformed-json",
            request = request,
        ),
    )

    @ExceptionHandler(AnonymousSubmissionDisabledException::class)
    fun handleAnonymousDisabled(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.FORBIDDEN,
            title = "Anonymous submission is disabled",
            detail = "This installation currently requires customer registration.",
            type = "/problems/anonymous-submission-disabled",
            request = request,
        ),
    )

    @ExceptionHandler(RequestNotFoundException::class)
    fun handleRequestNotFound(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = respond(
        problem(
            status = HttpStatus.NOT_FOUND,
            title = "Request not found",
            detail = "The ticket number and access token do not identify a visible request.",
            type = "/problems/request-not-found",
            request = request,
        ),
    )

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
        type: String,
        request: HttpServletRequest,
    ): ProblemDetail = ProblemDetail.forStatusAndDetail(status, detail).apply {
        this.title = title
        this.type = URI.create(type)
        this.instance = URI.create(request.requestURI)
        setProperty(
            "requestId",
            request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString(),
        )
    }

    private fun respond(problem: ProblemDetail): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(problem.status).body(problem)
}
