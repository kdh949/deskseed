package dev.deskseed.settings.internal

import dev.deskseed.settings.AnonymousSubmissionDisabledException
import dev.deskseed.settings.CustomerAccessMode
import dev.deskseed.settings.CustomerAccessPolicy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
internal class JpaCustomerAccessPolicy(
    private val repository: SystemSettingsRepository,
) : CustomerAccessPolicy {
    override fun currentMode(): CustomerAccessMode = settings().customerAccessMode

    override fun requireAnonymousSubmissionAllowed() {
        if (currentMode() == CustomerAccessMode.REGISTRATION_REQUIRED) {
            throw AnonymousSubmissionDisabledException()
        }
    }

    private fun settings(): SystemSettingsEntity = repository
        .findById(SystemSettingsEntity.SINGLETON_ID)
        .orElseThrow { IllegalStateException("The singleton system settings row is missing") }
}
