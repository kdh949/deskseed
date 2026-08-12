package dev.deskseed.settings.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.settings.CustomerAccessAdministration
import dev.deskseed.settings.CustomerAccessModeConflictException
import dev.deskseed.settings.CustomerAccessSetting
import dev.deskseed.settings.UpdateCustomerAccessModeCommand
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
internal class JpaCustomerAccessAdministration(
    private val repository: SystemSettingsRepository,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) : CustomerAccessAdministration {
    @Transactional(readOnly = true)
    override fun get(): CustomerAccessSetting = repository.findById(SystemSettingsEntity.SINGLETON_ID)
        .orElseThrow { IllegalStateException("The singleton system settings row is missing") }
        .toView()

    @Transactional
    override fun update(command: UpdateCustomerAccessModeCommand): CustomerAccessSetting {
        require(command.context.source == RequestSource.ADMIN_UI)
        require(command.expectedVersion >= 0)
        val settings = repository.lockSingleton()
            ?: throw IllegalStateException("The singleton system settings row is missing")
        if (settings.version != command.expectedVersion) {
            throw CustomerAccessModeConflictException(settings.version)
        }
        val previous = settings.customerAccessMode
        if (previous == command.mode) return settings.toView()
        val now = Instant.now(clock)
        settings.customerAccessMode = command.mode
        settings.updatedAt = now
        repository.saveAndFlush(settings)
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "CUSTOMER_ACCESS_MODE_CHANGED",
                actorType = ActorType.STAFF,
                actorId = command.actorId,
                actorDisplaySnapshot = command.actorDisplayName,
                source = command.context.source,
                targetType = "SYSTEM_SETTINGS",
                targetId = null,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = command.context.requestId,
                correlationId = command.context.correlationId,
                metadata = mapOf(
                    "previousMode" to previous.name,
                    "newMode" to command.mode.name,
                    "resultVersion" to settings.version.toString(),
                ),
                occurredAt = now,
            ),
        )
        return settings.toView()
    }

    private fun SystemSettingsEntity.toView() = CustomerAccessSetting(customerAccessMode, version, updatedAt)
}
