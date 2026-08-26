package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.customerSessionCookie
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
import kotlin.math.ceil

@RestController
@Validated
internal class CustomerPasswordAuthenticationController(
    private val authenticationService: CustomerPasswordAuthenticationService,
    private val clientAddressResolver: CustomerAuthClientAddressResolver,
) {
    @PostMapping("/api/v1/customer/auth/password-sessions")
    fun createPasswordSession(
        @Valid @RequestBody body: CustomerPasswordSessionRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<CurrentCustomerResponse> {
        val session = authenticationService.login(
            emailInput = body.email,
            rawPassword = body.password,
            remoteAddress = clientAddressResolver.resolve(request),
            previousRawSession = request.customerSessionCookie(),
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        response.addCookie(CustomerMagicLinkController.sessionCookie(session.rawToken))
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(session.principal.toResponse())
    }
}

@RestControllerAdvice(assignableTypes = [CustomerPasswordAuthenticationController::class])
internal class CustomerPasswordAuthenticationExceptionHandler(
    private val problemWriter: CustomerSecurityProblemWriter,
) {
    @ExceptionHandler(CustomerPasswordCredentialsInvalidException::class)
    fun invalidCredentials(
        exception: CustomerPasswordCredentialsInvalidException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            401,
            "/problems/customer-credentials-invalid",
            "고객 인증 정보를 확인할 수 없습니다",
            "이메일과 비밀번호를 확인해 주세요.",
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
        CustomerPasswordAuthenticationUnavailableException::class,
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

@Schema(description = "고객 password 세션 생성 요청")
internal data class CustomerPasswordSessionRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
    @field:NotBlank @field:Size(max = 256) val password: String,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORD SESSION HTTP REQUEST]"
}
