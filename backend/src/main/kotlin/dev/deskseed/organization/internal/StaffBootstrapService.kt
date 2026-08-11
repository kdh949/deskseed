package dev.deskseed.organization.internal

import dev.deskseed.audit.AdminSecurityAudit
import dev.deskseed.audit.AdminSecurityAuditWriter
import dev.deskseed.audit.AdminSecurityOutcome
import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import dev.deskseed.organization.StaffRole
import dev.deskseed.organization.StaffStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class StaffBootstrapService(
    private val repository: StaffAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val auditWriter: AdminSecurityAuditWriter,
    private val clock: Clock,
) {
    @Transactional
    fun bootstrap(email: String, displayName: String, password: String): UUID? {
        if (repository.count() > 0) return null
        val now = Instant.now(clock)
        val id = UUID.randomUUID()
        repository.saveAndFlush(
            StaffAccountEntity(
                id = id,
                emailNormalized = email.trim().lowercase(),
                emailDisplay = email.trim(),
                displayName = displayName.trim(),
                role = StaffRole.ADMIN,
                status = StaffStatus.ACTIVE,
                passwordHash = requireNotNull(passwordEncoder.encode(password)),
                createdAt = now,
                updatedAt = now,
            ),
        )
        auditWriter.append(
            AdminSecurityAudit(
                eventType = "STAFF_CREATED",
                actorType = ActorType.SYSTEM,
                actorId = null,
                actorDisplaySnapshot = "First-admin bootstrap",
                source = RequestSource.SYSTEM_JOB,
                targetType = "STAFF_ACCOUNT",
                targetId = id,
                outcome = AdminSecurityOutcome.SUCCEEDED,
                requestId = "bootstrap-${UUID.randomUUID()}",
                correlationId = "bootstrap-$id",
                metadata = mapOf(
                    "role" to StaffRole.ADMIN.name,
                    "credentialSource" to "PASSWORD_FILE",
                ),
                occurredAt = now,
            ),
        )
        return id
    }
}
