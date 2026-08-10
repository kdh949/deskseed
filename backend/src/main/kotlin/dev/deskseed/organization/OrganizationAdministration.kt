package dev.deskseed.organization

import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.UUID

data class AdminActorContext(
    val staffId: UUID,
    val displayName: String,
    val source: RequestSource,
    val requestId: String,
    val correlationId: String,
) {
    init {
        require(source == RequestSource.ADMIN_UI) { "Organization administration must use ADMIN_UI source" }
    }
}

data class GroupReference(
    val id: UUID,
    val name: String,
)

data class StaffAccountView(
    val id: UUID,
    val email: String,
    val displayName: String,
    val role: StaffRole,
    val status: StaffStatus,
    val memberships: List<GroupReference>,
    val lastLoginAt: Instant?,
)

data class SupportGroupView(
    val id: UUID,
    val name: String,
    val status: OrganizationStatus,
    val memberCount: Int,
)

data class GroupMembershipView(
    val groupId: UUID,
    val staffId: UUID,
    val staffDisplayName: String,
    val role: StaffRole,
)

enum class OrganizationStatus {
    ACTIVE,
    DISABLED,
}

data class CreateStaffAccountCommand(
    val email: String,
    val displayName: String,
    val role: StaffRole,
    val password: String,
)

interface OrganizationAdministration {
    fun listStaff(): List<StaffAccountView>

    fun createStaff(command: CreateStaffAccountCommand, actor: AdminActorContext): StaffAccountView

    fun disableStaff(staffId: UUID, actor: AdminActorContext)

    fun listGroups(): List<SupportGroupView>

    fun createGroup(name: String, actor: AdminActorContext): SupportGroupView

    fun renameGroup(groupId: UUID, name: String, actor: AdminActorContext): SupportGroupView

    fun disableGroup(groupId: UUID, actor: AdminActorContext)

    fun listGroupMembers(groupId: UUID): List<GroupMembershipView>

    fun addGroupMember(groupId: UUID, staffId: UUID, actor: AdminActorContext): GroupMembershipView

    fun removeGroupMember(groupId: UUID, staffId: UUID, actor: AdminActorContext)
}

class OrganizationNotFoundException(val code: String) : RuntimeException()

class OrganizationConflictException(val code: String) : RuntimeException()
