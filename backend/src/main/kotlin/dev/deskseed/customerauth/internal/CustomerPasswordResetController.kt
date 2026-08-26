package dev.deskseed.customerauth.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.ConstraintViolationException
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil

@RestController
@Validated
internal class CustomerPasswordResetController(
    private val resetService: CustomerPasswordResetApplicationService,
    private val properties: CustomerAuthProperties,
    private val clientAddressResolver: CustomerAuthClientAddressResolver,
) {
    @PostMapping("/api/v1/customer/auth/password-reset-requests")
    fun requestPasswordReset(
        @Valid @RequestBody body: CustomerPasswordResetRequest,
        request: HttpServletRequest,
    ): ResponseEntity<GenericAccepted> {
        val startedAt = System.nanoTime()
        resetService.request(
            emailInput = body.email,
            remoteAddress = clientAddressResolver.resolve(request),
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        padResponse(startedAt, properties.responseMinDuration)
        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(GenericAccepted(true))
    }

    @PostMapping("/api/v1/customer/auth/password-resets")
    fun resetPassword(
        @Valid @RequestBody body: CustomerPasswordResetConsumeRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        resetService.consume(
            rawToken = body.token,
            newPassword = body.newPassword,
            remoteAddress = clientAddressResolver.resolve(request),
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        response.addCookie(CustomerMagicLinkController.expiredSessionCookie())
        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .build()
    }

    private fun padResponse(startedAt: Long, minimum: Duration) {
        val deadline = startedAt + minimum.toNanos()
        var interrupted = false
        while (true) {
            if (Thread.interrupted()) interrupted = true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            LockSupport.parkNanos(remaining)
        }
        if (Thread.interrupted()) interrupted = true
        if (interrupted) Thread.currentThread().interrupt()
    }
}

@RestControllerAdvice(assignableTypes = [CustomerPasswordResetController::class])
internal class CustomerPasswordResetExceptionHandler(
    private val problemWriter: CustomerSecurityProblemWriter,
) {
    @ExceptionHandler(CustomerPasswordResetInvalidException::class)
    fun invalidProof(
        exception: CustomerPasswordResetInvalidException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            401,
            "/problems/customer-one-time-proof-invalid",
            "고객 확인 정보를 사용할 수 없습니다",
            "확인 정보가 만료되었거나 이미 사용되었습니다.",
        )
    }

    @ExceptionHandler(CustomerAuthenticationRateLimitedException::class)
    fun rateLimited(
        exception: CustomerAuthenticationRateLimitedException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.setHeader(
            "Retry-After",
            ceil(exception.retryAfter.toMillis() / 1000.0).toLong().coerceAtLeast(1).toString(),
        )
        problemWriter.write(
            response,
            request,
            429,
            "/problems/customer-authentication-rate-limited",
            "잠시 후 다시 시도해 주세요",
            "잠시 후 다시 시도해 주세요.",
        )
    }

    @ExceptionHandler(
        AuthenticationAttemptLimiterUnavailableException::class,
        CustomerPasswordResetUnavailableException::class,
    )
    fun unavailable(
        exception: RuntimeException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            503,
            "/problems/customer-authentication-unavailable",
            "고객 인증 요청을 안전하게 완료할 수 없습니다",
            "잠시 후 다시 시도해 주세요.",
        )
    }

    @ExceptionHandler(
        IllegalArgumentException::class,
        ConstraintViolationException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalidRequest(
        exception: Exception,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            400,
            "/problems/customer-auth-request-invalid",
            "Customer authentication request is invalid",
            "Check the request and try again.",
        )
    }
}

@Schema(description = "고객 password reset proof 발급 요청")
internal data class CustomerPasswordResetRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD RESET HTTP REQUEST]"
}

@Schema(description = "일회성 reset proof로 고객 password를 교체하는 요청")
internal data class CustomerPasswordResetConsumeRequest(
    @field:NotBlank @field:Size(min = 32, max = 256) val token: String,
    @field:Schema(minLength = 12, maxLength = 128)
    @field:NotBlank @field:Size(max = 256) val newPassword: String,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD RESET CONSUME HTTP REQUEST]"
}
