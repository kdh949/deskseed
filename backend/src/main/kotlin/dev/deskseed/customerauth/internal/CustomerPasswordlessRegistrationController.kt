package dev.deskseed.customerauth.internal

import dev.deskseed.customerauth.CustomerPrincipal
import dev.deskseed.customerauth.customerSessionCookie
import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@Validated
internal class CustomerPasswordlessRegistrationController(
    private val completionService: CustomerPasswordlessRegistrationCompletionService,
    private val clientAddressResolver: CustomerAuthClientAddressResolver,
) {
    @PutMapping("/api/v1/customer/me/registration")
    fun completeRegistration(
        @Valid @RequestBody body: CustomerPasswordlessRegistrationRequest,
        @AuthenticationPrincipal principal: CustomerPrincipal,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<CurrentCustomerResponse> {
        val session = completionService.complete(
            principal = principal,
            rawSession = requireNotNull(request.customerSessionCookie()),
            command = CustomerPasswordlessRegistrationCommand(
                password = body.password,
                displayName = body.displayName,
                companyName = body.companyName,
                acceptedPolicies = body.acceptedPolicies.map {
                    CustomerPasswordlessRegistrationPolicyVersion(it.policyKey, it.version)
                },
            ),
            remoteAddress = clientAddressResolver.resolve(request),
            context = CommandContexts.from(request, RequestSource.CUSTOMER_PORTAL),
        )
        response.addCookie(CustomerMagicLinkController.sessionCookie(session.rawToken))
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("Referrer-Policy", "no-referrer")
            .body(session.principal.toResponse())
    }
}

@Schema(description = "Passwordless 고객의 password/profile/current consent 등록 완료 요청")
internal data class CustomerPasswordlessRegistrationRequest(
    @field:NotBlank @field:Size(min = 12, max = 256) val password: String,
    @field:NotBlank @field:Size(max = 200) val displayName: String,
    @field:NotBlank @field:Size(max = 320) val companyName: String,
    @field:Valid @field:Size(min = 1, max = 20) val acceptedPolicies: List<AcceptedRegistrationPolicyVersion>,
) {
    override fun toString(): String = "[PROTECTED CUSTOMER PASSWORDLESS REGISTRATION HTTP REQUEST]"
}
