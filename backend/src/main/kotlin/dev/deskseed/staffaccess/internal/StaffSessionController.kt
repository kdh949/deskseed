package dev.deskseed.staffaccess.internal

import dev.deskseed.collaboration.CollaborationPresenceConnectionTerminator
import dev.deskseed.foundation.RequestIdFilter
import dev.deskseed.organization.StaffRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api/v1/agent")
@Validated
internal class StaffSessionController(
    private val authenticationService: StaffAuthenticationApplicationService,
    private val collaborationConnectionTerminator: CollaborationPresenceConnectionTerminator,
    private val clock: Clock,
    @Value("\${deskseed.staff-auth.session-idle:60m}")
    private val sessionIdle: Duration,
    @Value("\${deskseed.staff-auth.session-absolute:12h}")
    private val sessionAbsolute: Duration,
) {
    private val securityContextRepository = HttpSessionSecurityContextRepository()

    @GetMapping("/csrf")
    fun csrf(csrfToken: CsrfToken): ResponseEntity<StaffCsrfResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(StaffCsrfResponse(token = csrfToken.token, headerName = csrfToken.headerName))

    @PostMapping("/session")
    fun login(
        @Valid @RequestBody requestBody: StaffLoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        val identity = authenticationService.login(
            email = requestBody.email,
            password = requestBody.password,
            remoteAddress = request.remoteAddr ?: "unknown",
            requestId = request.requestId(),
            correlationId = request.correlationId(),
        )
        val session = request.getSession(true)
        request.changeSessionId()
        val now = Instant.now(clock)
        session.maxInactiveInterval = sessionIdle.seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        session.setAttribute(StaffSessionValidationFilter.ABSOLUTE_EXPIRES_AT, now.plus(sessionAbsolute))
        session.setAttribute(StaffSessionValidationFilter.LAST_ACTIVITY_AT, now)
        session.setAttribute(StaffSessionValidationFilter.AUTHENTICATED_AT, now)

        val principal = StaffPrincipal.from(identity)
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            principal,
            null,
            buildList {
                add(SimpleGrantedAuthority("ROLE_${principal.role.name}"))
                principal.authorities.sorted().forEach { add(SimpleGrantedAuthority(it)) }
            },
        )
        val context = SecurityContextHolder.createEmptyContext().apply {
            this.authentication = authentication
        }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/session")
    fun logout(
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        collaborationConnectionTerminator.closeStaffConnections(principal.id)
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun currentStaff(
        @AuthenticationPrincipal principal: StaffPrincipal,
    ): CurrentStaffResponse = CurrentStaffResponse(
        id = principal.id.toString(),
        email = principal.email,
        displayName = principal.displayName,
        role = principal.role,
        capabilities = principal.authorities.sorted(),
    )

    private fun HttpServletRequest.requestId(): String =
        getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)?.toString() ?: "missing-request-id"

    private fun HttpServletRequest.correlationId(): String =
        getAttribute(RequestIdFilter.CORRELATION_ID_ATTRIBUTE)?.toString() ?: requestId()
}

@Schema(description = "직원 세션에 귀속된 CSRF 토큰")
internal data class StaffCsrfResponse(
    val token: String,
    val headerName: String,
)

@Schema(description = "직원 로그인 요청")
internal data class StaffLoginRequest(
    @field:Schema(description = "직원 계정 이메일", example = "agent@example.com")
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,

    @field:Schema(
        description = "직원 비밀번호. 문서나 로그에 저장하지 않습니다.",
        example = "not-a-real-password",
        accessMode = Schema.AccessMode.WRITE_ONLY,
    )
    @field:NotBlank
    @field:Size(max = 128)
    val password: String,
)

@Schema(description = "현재 인증된 직원과 보유 권한")
internal data class CurrentStaffResponse(
    val id: String,
    val email: String,
    val displayName: String,
    val role: StaffRole,
    val capabilities: List<String>,
)
