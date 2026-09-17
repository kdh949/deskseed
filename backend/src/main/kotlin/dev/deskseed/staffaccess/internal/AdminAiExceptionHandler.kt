package dev.deskseed.staffaccess.internal

import dev.deskseed.aiassistance.AiSettingsConflictException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice(assignableTypes = [AdminAiController::class])
internal class AdminAiExceptionHandler {
    @ExceptionHandler(AiSettingsConflictException::class)
    fun conflict(request: HttpServletRequest) = problem(
        request, HttpStatus.CONFLICT, "/problems/ai-settings-conflict", "AI settings conflict",
        "The settings version or operation identity conflicts with current state.",
    )

    @ExceptionHandler(IllegalArgumentException::class, ConstraintViolationException::class, MethodArgumentNotValidException::class)
    fun invalid(request: HttpServletRequest) = problem(
        request, HttpStatus.BAD_REQUEST, "/problems/ai-settings-invalid", "AI settings invalid",
        "The requested AI configuration is not allowed.",
    )

    @ExceptionHandler(DataAccessException::class)
    fun unavailable(request: HttpServletRequest) = problem(
        request, HttpStatus.SERVICE_UNAVAILABLE, "/problems/ai-administration-unavailable",
        "AI administration unavailable", "Required persistence or audit storage is unavailable.",
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
        }
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body)
    }
}
