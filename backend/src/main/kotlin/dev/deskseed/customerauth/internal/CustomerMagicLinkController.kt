package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.CustomerCsrfFilter
import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.customerauth.customerSessionCookie
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import kotlin.math.ceil

@RestController
@Validated
internal class CustomerMagicLinkController(
    private val authenticationService: CustomerMagicLinkAuthenticationService,
    private val properties: CustomerAuthProperties,
    private val clientAddressResolver: CustomerAuthClientAddressResolver,
    private val problemWriter: CustomerSecurityProblemWriter,
) {
    @PostMapping("/api/v1/customer/auth/magic-link-requests")
    fun requestMagicLink(
        @Valid @RequestBody body: MagicLinkRequest,
        request: HttpServletRequest,
    ): ResponseEntity<GenericAccepted> {
        val startedAt = System.nanoTime()
        authenticationService.request(
            email = body.email,
            remoteAddress = clientAddressResolver.resolve(request),
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        padResponse(startedAt, properties.responseMinDuration)
        return ResponseEntity.accepted()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(GenericAccepted(true))
    }

    @PostMapping("/api/v1/customer/auth/magic-link-sessions")
    fun consumeMagicLink(
        @Valid @RequestBody body: MagicLinkConsumeRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<CurrentCustomerResponse> {
        val session = authenticationService.consume(
            rawToken = body.token,
            remoteAddress = clientAddressResolver.resolve(request),
            previousRawSession = request.customerSessionCookie(),
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        response.addCookie(sessionCookie(session.rawToken))
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(session.principal.toResponse())
    }

    @GetMapping("/api/v1/customer/me")
    fun currentCustomer(@AuthenticationPrincipal principal: CustomerPrincipal): ResponseEntity<CurrentCustomerResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(principal.toResponse())

    @GetMapping("/api/v1/customer/csrf")
    fun csrf(
        request: HttpServletRequest,
        @AuthenticationPrincipal principal: CustomerPrincipal,
    ): ResponseEntity<CustomerCsrfResponse> {
        @Suppress("UNUSED_VARIABLE") val authenticated = principal
        val rawSession = requireNotNull(request.customerSessionCookie())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            CustomerCsrfResponse(
                token = CustomerAuthSecrets.csrf(properties.csrfKey, rawSession),
                headerName = CustomerCsrfFilter.CSRF_HEADER,
            ),
        )
    }

    @DeleteMapping("/api/v1/customer/session")
    fun logout(request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Void> {
        request.customerSessionCookie()?.let {
            authenticationService.logout(it, CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL))
        }
        response.addCookie(expiredSessionCookie())
        SecurityContextHolder.clearContext()
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @ExceptionHandler(CustomerMagicLinkInvalidException::class)
    fun invalidMagicLink(
        exception: CustomerMagicLinkInvalidException,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        @Suppress("UNUSED_VARIABLE") val ignored = exception
        problemWriter.write(
            response,
            request,
            401,
            "/problems/customer-one-time-proof-invalid",
            "일회성 인증 정보를 확인할 수 없습니다",
            "인증 정보가 올바르지 않거나 만료되었거나 이미 사용되었습니다.",
        )
    }

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
        CustomerMagicLinkUnavailableException::class,
    )
    fun limiterUnavailable(
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

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidRequest(
        exception: IllegalArgumentException,
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

    private fun padResponse(startedAt: Long, minimum: Duration) {
        val remaining = minimum.toNanos() - (System.nanoTime() - startedAt)
        if (remaining > 0) LockSupport.parkNanos(remaining)
    }

    companion object {
        const val CUSTOMER_COOKIE = "DESKSEED_CUSTOMER_SESSION"

        fun sessionCookie(value: String) = Cookie(CUSTOMER_COOKIE, value).apply {
            isHttpOnly = true
            secure = true
            path = "/"
            setAttribute("SameSite", "Lax")
        }

        fun expiredSessionCookie() = sessionCookie("").apply { maxAge = 0 }
    }
}

@Schema(description = "고객 매직 링크 발송 요청")
internal data class MagicLinkRequest(
    @field:Schema(description = "검증 링크를 받을 고객 이메일", example = "customer@example.com")
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
) {
    override fun toString(): String = "[PROTECTED MAGIC LINK REQUEST]"
}

@Schema(description = "일회성 매직 링크 소비 요청")
internal data class MagicLinkConsumeRequest(
    @field:Schema(
        description = "1~256자 non-blank opaque proof. 발급 형식과 다른 bounded 값도 generic invalid-proof로 처리합니다.",
        example = "example-token-not-valid-0000000000000000",
        minLength = 1,
        maxLength = 256,
        pattern = "^(?![\\s\\S]*[\\x00-\\x1F\\x7F])(?=[\\s\\S]*\\S)[\\s\\S]+$",
    )
    @field:NotBlank @field:Size(max = 256) val token: String,
) {
    override fun toString(): String = "[PROTECTED MAGIC LINK CONSUME REQUEST]"
}

@Schema(description = "요청 수락 여부만 표시하는 열거 방지 응답")
internal data class GenericAccepted(val accepted: Boolean)

@Schema(description = "현재 인증된 고객 계정")
internal data class CurrentCustomerResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val companyName: String?,
    val verifiedAt: String,
    val credentialState: dev.deskseed.customerauth.CustomerCredentialState,
    val registrationState: dev.deskseed.customerauth.CustomerRegistrationState,
    val availableAuthenticationMethods: List<dev.deskseed.customerauth.CustomerAuthenticationMethod>,
) {
    override fun toString(): String = "[PROTECTED CURRENT CUSTOMER RESPONSE]"
}

internal fun CustomerPrincipal.toResponse() = CurrentCustomerResponse(
    id = customerId.toString(),
    email = email,
    displayName = displayName,
    companyName = companyName,
    verifiedAt = verifiedAt.toString(),
    credentialState = credentialState,
    registrationState = registrationState,
    availableAuthenticationMethods = availableAuthenticationMethods,
)

@Schema(description = "고객 세션에 귀속된 CSRF 토큰")
internal data class CustomerCsrfResponse(val token: String, val headerName: String)
