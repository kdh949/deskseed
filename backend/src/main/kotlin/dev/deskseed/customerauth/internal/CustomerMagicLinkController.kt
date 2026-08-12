package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.CustomerCsrfFilter
import dev.deskseed.customerauth.customerSessionCookie
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
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

@RestController
@Validated
internal class CustomerMagicLinkController(
    private val authenticationService: CustomerMagicLinkAuthenticationService,
    private val properties: CustomerAuthProperties,
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
            remoteAddress = request.remoteAddr ?: "unknown",
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
            "/problems/customer-magic-link-invalid",
            "Customer magic link is invalid",
            "The link is invalid, expired, or already used. Request a new link.",
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

    private fun CustomerPrincipal.toResponse() = CurrentCustomerResponse(
        id = customerId.toString(),
        email = email,
        displayName = displayName,
        verifiedAt = verifiedAt.toString(),
    )

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

internal data class MagicLinkRequest(
    @field:NotBlank @field:Email @field:Size(max = 254) val email: String,
)

internal data class MagicLinkConsumeRequest(
    @field:NotBlank @field:Size(max = 256) val token: String,
)

internal data class GenericAccepted(val accepted: Boolean)

internal data class CurrentCustomerResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val verifiedAt: String,
)

internal data class CustomerCsrfResponse(val token: String, val headerName: String)
