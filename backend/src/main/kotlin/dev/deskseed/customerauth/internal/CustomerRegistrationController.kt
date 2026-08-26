package dev.deskseed.customerauth.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.ConstraintViolationException
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
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
internal class CustomerRegistrationController(
    private val registrationService: CustomerRegistrationApplicationService,
    private val verificationService: CustomerRegistrationVerificationService,
    private val properties: CustomerAuthProperties,
) {
    @PostMapping("/api/v1/customer/registrations")
    fun requestRegistration(
        @Valid @RequestBody body: CustomerRegistrationRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<GenericAccepted> {
        val startedAt = System.nanoTime()
        val result = registrationService.request(
            command = CustomerRegistrationRequestCommand(
                email = body.email,
                password = body.password,
                displayName = body.displayName,
                companyName = body.companyName,
                acceptedPolicies = body.acceptedPolicies.map {
                    RequestedRegistrationPolicyVersion(it.policyKey, it.version)
                },
            ),
            remoteAddress = request.remoteAddr ?: "unknown",
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        response.addCookie(continuationCookie(result.rawContinuationSecret, properties.registrationVerificationTtl))
        padResponse(startedAt, properties.responseMinDuration)
        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(GenericAccepted(true))
    }

    @PostMapping("/api/v1/customer/registration-verifications")
    fun verifyRegistration(
        @Valid @RequestBody body: CustomerRegistrationVerificationRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        verificationService.verify(
            rawToken = body.token,
            rawContinuationSecret = request.registrationContinuationCookie(),
            remoteAddress = request.remoteAddr ?: "unknown",
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        response.addCookie(expiredContinuationCookie())
        return ResponseEntity.noContent()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .build()
    }

    private fun padResponse(startedAt: Long, minimum: Duration) {
        val remaining = minimum.toNanos() - (System.nanoTime() - startedAt)
        if (remaining > 0) LockSupport.parkNanos(remaining)
    }

    companion object {
        const val REGISTRATION_COOKIE = "DESKSEED_CUSTOMER_REGISTRATION"

        fun continuationCookie(value: String, ttl: Duration) = Cookie(REGISTRATION_COOKIE, value).apply {
            isHttpOnly = true
            secure = true
            path = "/api/v1/customer/registration-verifications"
            maxAge = ttl.seconds.toInt()
            setAttribute("SameSite", "Lax")
        }

        fun expiredContinuationCookie() = continuationCookie("", Duration.ZERO).apply { maxAge = 0 }
    }
}

@RestControllerAdvice(assignableTypes = [CustomerRegistrationController::class])
internal class CustomerRegistrationExceptionHandler(
    private val problemWriter: CustomerSecurityProblemWriter,
) {
    @ExceptionHandler(CustomerAuthenticationRateLimitedException::class)
    fun rateLimited(
        exception: CustomerAuthenticationRateLimitedException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val retrySeconds = ceil(exception.retryAfter.toMillis() / 1000.0).toLong().coerceAtLeast(1)
        response.setHeader("Retry-After", retrySeconds.toString())
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
        CustomerRegistrationUnavailableException::class,
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

    @ExceptionHandler(CustomerRegistrationVerificationInvalidException::class)
    fun invalidVerification(
        exception: CustomerRegistrationVerificationInvalidException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            401,
            "/problems/customer-registration-verification-invalid",
            "Customer registration verification is invalid",
            "The verification proof is invalid, expired, or already used.",
        )
    }

    @ExceptionHandler(CustomerRegistrationVerificationConflictException::class)
    fun verificationConflict(
        exception: CustomerRegistrationVerificationConflictException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            409,
            "/problems/customer-registration-conflict",
            "Customer registration state changed",
            "Restart registration with the current policy state.",
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

@Schema(description = "고객 password 등록과 이메일 확인 요청")
internal data class CustomerRegistrationRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(max = 256) val password: String,
    @field:NotBlank @field:Size(max = 200) val displayName: String,
    @field:NotBlank @field:Size(max = 320) val companyName: String,
    @field:Valid @field:Size(min = 1, max = 20) val acceptedPolicies: List<AcceptedRegistrationPolicyVersion>,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER REGISTRATION HTTP REQUEST]"
}

internal data class AcceptedRegistrationPolicyVersion(
    @field:NotBlank
    @field:Size(max = 80)
    @field:Pattern(regexp = "^[a-z][a-z0-9]*(?:-[a-z0-9]+)*\$")
    val policyKey: String,
    @field:Min(1) val version: Int,
)

@Schema(description = "이메일 token과 browser continuation proof를 함께 소비하는 등록 검증 요청")
internal data class CustomerRegistrationVerificationRequest(
    @field:NotBlank @field:Size(min = 32, max = 256) val token: String,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER REGISTRATION VERIFICATION HTTP REQUEST]"
}

private fun HttpServletRequest.registrationContinuationCookie(): String? =
    cookies?.filter { it.name == CustomerRegistrationController.REGISTRATION_COOKIE }
        ?.singleOrNull()
        ?.value
        ?.takeIf { it.length in 32..256 }
