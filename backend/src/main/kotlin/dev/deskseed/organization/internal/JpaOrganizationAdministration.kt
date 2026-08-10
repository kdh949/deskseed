package dev.deskseed.organization.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.organization.AdminActorContext
import dev.deskseed.organization.CreateStaffAccountCommand
import dev.deskseed.organization.GroupMembershipView
import dev.deskseed.organization.GroupReference
import dev.deskseed.organization.OrganizationAdministration
import dev.deskseed.organization.OrganizationConflictException
import dev.deskseed.organization.OrganizationNotFoundException
import dev.deskseed.organization.OrganizationStatus
import dev.deskseed.organization.StaffAccountView
import dev.deskseed.organization.StaffRole
import dev.deskseed.organization.StaffStatus
import dev.deskseed.organization.SupportGroupView
import dev.deskseed.ticketing.TicketAssignmentUsage
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class JpaOrganizationAdministration(
    private val staffRepository: StaffAccountRepository,
    private val groupRepository: SupportGroupRepository,
    private val membershipRepository: GroupMembershipRepository,
    private val ticketAssignmentUsage: TicketAssignmentUsage,
    private val passwordEncoder: PasswordEncoder,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : OrganizationAdministration {
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listStaff(): List<StaffAccountView> = staffRepository.findAllByOrderByDisplayNameAscIdAsc()
        .map(::staffView)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createStaff(
        command: CreateStaffAccountCommand,
        actor: AdminActorContext,
    ): StaffAccountView {
        val email = command.email.trim()
        val normalizedEmail = email.lowercase()
        if (staffRepository.findByEmailNormalized(normalizedEmail) != null) {
            throw OrganizationConflictException("DUPLICATE_STAFF_EMAIL")
        }
        val displayName = command.displayName.trim()
        require(displayName.isNotEmpty() && displayName.length <= 100)
        require(command.password.length in 12..128)
        val now = Instant.now(clock)
        val entity = staffRepository.saveAndFlush(
            StaffAccountEntity(
                id = UUID.randomUUID(),
                emailNormalized = normalizedEmail,
                emailDisplay = email,
                displayName = displayName,
                role = command.role,
                status = StaffStatus.ACTIVE,
                passwordHash = requireNotNull(passwordEncoder.encode(command.password)),
                createdAt = now,
                updatedAt = now,
            ),
        )
        audit(
            eventType = "STAFF_CREATED",
            actor = actor,
            targetType = "STAFF_ACCOUNT",
            targetId = entity.id,
            metadata = mapOf("role" to entity.role.name, "status" to entity.status.name),
            now = now,
        )
        return staffView(entity)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun disableStaff(staffId: UUID, actor: AdminActorContext) {
        if (staffId == actor.staffId) throw OrganizationConflictException("SELF_DISABLE_NOT_ALLOWED")
        val staff = staffRepository.findById(staffId)
            .orElseThrow { OrganizationNotFoundException("STAFF_NOT_FOUND") }
        if (staff.status == StaffStatus.DISABLED) return
        if (ticketAssignmentUsage.hasTicketsAssignedToStaff(staffId)) {
            throw OrganizationConflictException("STAFF_HAS_ASSIGNED_TICKETS")
        }
        if (staff.role == StaffRole.ADMIN &&
            staffRepository.countByRoleAndStatus(StaffRole.ADMIN, StaffStatus.ACTIVE) <= 1
        ) {
            throw OrganizationConflictException("LAST_ACTIVE_ADMIN")
        }
        val now = Instant.now(clock)
        membershipRepository.findAllByStaffIdAndStatusOrderByGroupIdAsc(
            staffId,
            GroupMembershipStatus.ACTIVE,
        ).forEach { membership ->
            membership.status = GroupMembershipStatus.INACTIVE
            membership.updatedAt = now
            auditMembership(actor, membership, "REMOVED_BY_STAFF_DISABLE", now)
        }
        staff.status = StaffStatus.DISABLED
        staff.updatedAt = now
        audit(
            eventType = "STAFF_DISABLED",
            actor = actor,
            targetType = "STAFF_ACCOUNT",
            targetId = staff.id,
            metadata = mapOf("role" to staff.role.name),
            now = now,
        )
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listGroups(): List<SupportGroupView> = groupRepository.findAllByOrderByNameAscIdAsc()
        .map(::groupView)

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun createGroup(name: String, actor: AdminActorContext): SupportGroupView {
        val normalizedName = validatedGroupName(name)
        if (groupRepository.findByNameIgnoreCase(normalizedName) != null) {
            throw OrganizationConflictException("DUPLICATE_GROUP_NAME")
        }
        val now = Instant.now(clock)
        val group = groupRepository.saveAndFlush(
            SupportGroupEntity(
                id = UUID.randomUUID(),
                name = normalizedName,
                status = OrganizationStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )
        audit("GROUP_CREATED", actor, "SUPPORT_GROUP", group.id, mapOf("name" to group.name), now)
        return groupView(group)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun renameGroup(groupId: UUID, name: String, actor: AdminActorContext): SupportGroupView {
        val group = activeGroup(groupId)
        val normalizedName = validatedGroupName(name)
        groupRepository.findByNameIgnoreCase(normalizedName)?.let { existing ->
            if (existing.id != group.id) throw OrganizationConflictException("DUPLICATE_GROUP_NAME")
        }
        val before = group.name
        val now = Instant.now(clock)
        group.name = normalizedName
        group.updatedAt = now
        audit(
            "GROUP_CHANGED",
            actor,
            "SUPPORT_GROUP",
            group.id,
            mapOf("field" to "name", "before" to before, "after" to group.name),
            now,
        )
        return groupView(group)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun disableGroup(groupId: UUID, actor: AdminActorContext) {
        val group = activeGroup(groupId)
        if (ticketAssignmentUsage.hasTicketsInGroup(groupId)) {
            throw OrganizationConflictException("GROUP_HAS_ASSIGNED_TICKETS")
        }
        val now = Instant.now(clock)
        membershipRepository.findAllByGroupIdAndStatusOrderByStaffIdAsc(
            groupId,
            GroupMembershipStatus.ACTIVE,
        ).forEach { membership ->
            membership.status = GroupMembershipStatus.INACTIVE
            membership.updatedAt = now
            auditMembership(actor, membership, "REMOVED_BY_GROUP_DISABLE", now)
        }
        group.status = OrganizationStatus.DISABLED
        group.updatedAt = now
        audit("GROUP_CHANGED", actor, "SUPPORT_GROUP", group.id, mapOf("status" to "DISABLED"), now)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    override fun listGroupMembers(groupId: UUID): List<GroupMembershipView> {
        activeGroup(groupId)
        return membershipRepository.findAllByGroupIdAndStatusOrderByStaffIdAsc(
            groupId,
            GroupMembershipStatus.ACTIVE,
        ).map(::membershipView)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun addGroupMember(
        groupId: UUID,
        staffId: UUID,
        actor: AdminActorContext,
    ): GroupMembershipView {
        activeGroup(groupId)
        val staff = staffRepository.findById(staffId)
            .filter { it.status == StaffStatus.ACTIVE }
            .orElseThrow { OrganizationNotFoundException("ACTIVE_STAFF_NOT_FOUND") }
        val now = Instant.now(clock)
        val existing = membershipRepository.findByGroupIdAndStaffId(groupId, staffId)
        if (existing?.status == GroupMembershipStatus.ACTIVE) {
            throw OrganizationConflictException("DUPLICATE_MEMBERSHIP")
        }
        val membership = if (existing == null) {
            membershipRepository.saveAndFlush(
                GroupMembershipEntity(
                    id = UUID.randomUUID(),
                    groupId = groupId,
                    staffId = staffId,
                    status = GroupMembershipStatus.ACTIVE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            existing.status = GroupMembershipStatus.ACTIVE
            existing.updatedAt = now
            existing
        }
        auditMembership(actor, membership, "ADDED", now)
        return GroupMembershipView(groupId, staff.id, staff.displayName, staff.role)
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    override fun removeGroupMember(groupId: UUID, staffId: UUID, actor: AdminActorContext) {
        activeGroup(groupId)
        val membership = membershipRepository.findByGroupIdAndStaffId(groupId, staffId)
            ?.takeIf { it.status == GroupMembershipStatus.ACTIVE }
            ?: throw OrganizationNotFoundException("ACTIVE_MEMBERSHIP_NOT_FOUND")
        if (ticketAssignmentUsage.hasTicketsAssignedToMember(groupId, staffId)) {
            throw OrganizationConflictException("MEMBER_HAS_ASSIGNED_TICKETS")
        }
        val now = Instant.now(clock)
        membership.status = GroupMembershipStatus.INACTIVE
        membership.updatedAt = now
        auditMembership(actor, membership, "REMOVED", now)
    }

    private fun staffView(staff: StaffAccountEntity): StaffAccountView {
        val groupIds = membershipRepository.findAllByStaffIdAndStatusOrderByGroupIdAsc(
            staff.id,
            GroupMembershipStatus.ACTIVE,
        ).map(GroupMembershipEntity::groupId)
        val groupNames = groupRepository.findAllById(groupIds).associateBy(SupportGroupEntity::id)
        return StaffAccountView(
            id = staff.id,
            email = staff.emailDisplay,
            displayName = staff.displayName,
            role = staff.role,
            status = staff.status,
            memberships = groupIds.mapNotNull { id -> groupNames[id]?.let { GroupReference(it.id, it.name) } },
            lastLoginAt = staff.lastLoginAt,
        )
    }

    private fun groupView(group: SupportGroupEntity): SupportGroupView = SupportGroupView(
        id = group.id,
        name = group.name,
        status = group.status,
        memberCount = membershipRepository.countByGroupIdAndStatus(group.id, GroupMembershipStatus.ACTIVE).toInt(),
    )

    private fun membershipView(membership: GroupMembershipEntity): GroupMembershipView {
        val staff = staffRepository.findById(membership.staffId)
            .orElseThrow { OrganizationNotFoundException("STAFF_NOT_FOUND") }
        return GroupMembershipView(membership.groupId, staff.id, staff.displayName, staff.role)
    }

    private fun activeGroup(groupId: UUID): SupportGroupEntity = groupRepository.findById(groupId)
        .filter { it.status == OrganizationStatus.ACTIVE }
        .orElseThrow { OrganizationNotFoundException("ACTIVE_GROUP_NOT_FOUND") }

    private fun validatedGroupName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 100)
    }

    private fun auditMembership(
        actor: AdminActorContext,
        membership: GroupMembershipEntity,
        action: String,
        now: Instant,
    ) = audit(
        eventType = "GROUP_MEMBERSHIP_CHANGED",
        actor = actor,
        targetType = "GROUP_MEMBERSHIP",
        targetId = membership.id,
        metadata = mapOf(
            "action" to action,
            "groupId" to membership.groupId.toString(),
            "staffId" to membership.staffId.toString(),
        ),
        now = now,
    )

    private fun audit(
        eventType: String,
        actor: AdminActorContext,
        targetType: String,
        targetId: UUID,
        metadata: Map<String, String>,
        now: Instant,
    ) {
        auditWriter.append(
            AdminSecurityAudit(
                eventType = eventType,
                actorType = ActorType.STAFF,
                actorId = actor.staffId,
                actorDisplaySnapshot = actor.displayName,
                source = actor.source,
                targetType = targetType,
                targetId = targetId,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = actor.requestId,
                correlationId = actor.correlationId,
                metadata = metadata,
                occurredAt = now,
            ),
        )
    }
}
