package dev.deskseed.organization

import java.util.UUID

data class AdminStaffDirectoryFilter(
    val query: String,
    val role: StaffRole? = null,
    val status: StaffStatus? = null,
    val memberOfGroupId: UUID? = null,
    val excludeGroupId: UUID? = null,
)

data class AdminGroupDirectoryFilter(
    val query: String,
    val status: OrganizationStatus? = null,
)
