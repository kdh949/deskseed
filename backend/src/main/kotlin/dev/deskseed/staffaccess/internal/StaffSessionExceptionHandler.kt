package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.RequestIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import kotlin.math.ceil

@RestControllerAdvice(assignableTypes = [StaffSessionController::class])
internal class StaffSessionExceptionHandler {
    @ExceptionHandler(InvalidStaffCredentialsException::class)
    fun invalidCredentials(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request = request,
        status = HttpStatus.UNAUTHORIZED,
        type = "/problems/invalid-staff-credentials",
        title = "Staff sign-in failed",
        detail = "The email or password is invalid.",
    )

    @ExceptionHandler(StaffLoginRateLimitedException::class)
    fun rateLimited(
        exception: StaffLoginRateLimitedException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val retrySeconds = ceil(exception.retryAfter.toMillis() / 1000.0).toLong().coerceAtLeast(1)
        return response(
            request = request,
            status = HttpStatus.TOO_MANY_REQUESTS,
            type = "/problems/staff-login-rate-limited",
            title = "Staff sign-in temporarily limited",
            detail = "Try again later.",
        ).also { it.headers.set("Retry-After", retrySeconds.toString()) }
    }

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        ConstraintViolationException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidRequest(request: HttpServletRequest): ResponseEntity<ProblemDetail> = response(
        request = request,
        status = HttpStatus.BAD_REQUEST,
        type = "/problems/validation",
        title = "Staff request validation failed",
        detail = "One or more request fields are invalid.",
    )

    private fun response(
        request: HttpServletRequest,
        status: HttpStatus,
        type: String,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create(type)
            this.title = title
            this.instance = URI.create(request.requestURI)
            setProperty(
                "requestId",
                request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString(),
            )
        }
        return ResponseEntity.status(status).body(problem)
    }
}
