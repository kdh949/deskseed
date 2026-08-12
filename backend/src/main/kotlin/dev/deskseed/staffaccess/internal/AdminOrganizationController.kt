package dev.deskseed.staffaccess.internal

import dev.deskseed.foundation.CommandContexts
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.AdminActorContext
import dev.deskseed.organization.CreateStaffAccountCommand
import dev.deskseed.organization.GroupMembershipView
import dev.deskseed.organization.GrantableAuditAuthority
import dev.deskseed.organization.OrganizationAdministration
import dev.deskseed.organization.OrganizationPage
import dev.deskseed.organization.StaffAccountView
import dev.deskseed.organization.StaffRole
import dev.deskseed.organization.SupportGroupView
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
@Validated
internal class AdminOrganizationController(
    private val administration: OrganizationAdministration,
) {
    @GetMapping("/staff")
    fun listStaff(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<List<StaffAccountView>> = pageResponse(administration.listStaff(page, size))

    @PostMapping("/staff")
    fun createStaff(
        @Valid @RequestBody body: CreateStaffRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<StaffAccountView> {
        val created = administration.createStaff(
            CreateStaffAccountCommand(
                email = body.email,
                displayName = body.displayName,
                role = body.role,
                password = body.password,
            ),
            request.actor(principal),
        )
        return ResponseEntity.created(URI.create("/api/v1/admin/staff/${created.id}")).body(created)
    }

    @DeleteMapping("/staff/{staffId}")
    fun disableStaff(
        @PathVariable staffId: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        administration.disableStaff(staffId, request.actor(principal))
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/staff/{staffId}/audit-authorities/{authority}")
    fun grantAuditAuthority(
        @PathVariable staffId: UUID,
        @PathVariable authority: GrantableAuditAuthority,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        administration.grantAuditAuthority(staffId, authority, request.actor(principal))
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/staff/{staffId}/audit-authorities/{authority}")
    fun revokeAuditAuthority(
        @PathVariable staffId: UUID,
        @PathVariable authority: GrantableAuditAuthority,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        administration.revokeAuditAuthority(staffId, authority, request.actor(principal))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/groups")
    fun listGroups(
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<List<SupportGroupView>> = pageResponse(administration.listGroups(page, size))

    @PostMapping("/groups")
    fun createGroup(
        @Valid @RequestBody body: GroupNameRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<SupportGroupView> {
        val created = administration.createGroup(body.name, request.actor(principal))
        return ResponseEntity.created(URI.create("/api/v1/admin/groups/${created.id}")).body(created)
    }

    @PatchMapping("/groups/{groupId}")
    fun renameGroup(
        @PathVariable groupId: UUID,
        @Valid @RequestBody body: GroupNameRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): SupportGroupView = administration.renameGroup(groupId, body.name, request.actor(principal))

    @DeleteMapping("/groups/{groupId}")
    fun disableGroup(
        @PathVariable groupId: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        administration.disableGroup(groupId, request.actor(principal))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/groups/{groupId}/members")
    fun listMembers(
        @PathVariable groupId: UUID,
        @RequestParam(defaultValue = "0") @Min(0) page: Int,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<List<GroupMembershipView>> = pageResponse(
        administration.listGroupMembers(groupId, page, size),
    )

    @PostMapping("/groups/{groupId}/members")
    fun addMember(
        @PathVariable groupId: UUID,
        @Valid @RequestBody body: GroupMembershipRequest,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<GroupMembershipView> {
        val created = administration.addGroupMember(groupId, body.staffId, request.actor(principal))
        return ResponseEntity.created(
            URI.create("/api/v1/admin/groups/$groupId/members/${created.staffId}"),
        ).body(created)
    }

    @DeleteMapping("/groups/{groupId}/members/{staffId}")
    fun removeMember(
        @PathVariable groupId: UUID,
        @PathVariable staffId: UUID,
        @AuthenticationPrincipal principal: StaffPrincipal,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        administration.removeGroupMember(groupId, staffId, request.actor(principal))
        return ResponseEntity.noContent().build()
    }

    private fun HttpServletRequest.actor(principal: StaffPrincipal): AdminActorContext {
        val context = CommandContexts.from(this, RequestSource.ADMIN_UI)
        return AdminActorContext(
            staffId = principal.id,
            displayName = principal.displayName,
            source = context.source,
            requestId = context.requestId,
            correlationId = context.correlationId,
        )
    }

    private fun <T> pageResponse(page: OrganizationPage<T>): ResponseEntity<List<T>> = ResponseEntity.ok()
        .header("X-Page-Number", page.page.toString())
        .header("X-Page-Size", page.size.toString())
        .header("X-Total-Count", page.totalCount.toString())
        .header("X-Total-Pages", page.totalPages.toString())
        .body(page.items)
}

internal data class CreateStaffRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 254)
    val email: String,
    @field:NotBlank
    @field:Size(max = 100)
    val displayName: String,
    val role: StaffRole,
    @field:NotBlank
    @field:Size(min = 12, max = 128)
    val password: String,
)

internal data class GroupNameRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
)

internal data class GroupMembershipRequest(
    val staffId: UUID,
)
