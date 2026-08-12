package dev.deskseed.settings

import dev.deskseed.foundation.CommandContext
import java.time.Instant
import java.util.UUID

enum class CustomerAccessMode {
    ANONYMOUS_ALLOWED,
    REGISTRATION_OPTIONAL,
    REGISTRATION_REQUIRED,
}

interface CustomerAccessPolicy {
    fun currentMode(): CustomerAccessMode
    fun requireAnonymousSubmissionAllowed()
}

data class CustomerAccessSetting(
    val mode: CustomerAccessMode,
    val version: Long,
    val updatedAt: Instant,
)

data class UpdateCustomerAccessModeCommand(
    val mode: CustomerAccessMode,
    val expectedVersion: Long,
    val actorId: UUID,
    val actorDisplayName: String,
    val context: CommandContext,
)

interface CustomerAccessAdministration {
    fun get(): CustomerAccessSetting
    fun update(command: UpdateCustomerAccessModeCommand): CustomerAccessSetting
}

class CustomerAccessModeConflictException(val currentVersion: Long) : RuntimeException()

class AnonymousSubmissionDisabledException : RuntimeException()
