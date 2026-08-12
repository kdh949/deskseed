package dev.deskseed.organization.internal

import dev.deskseed.organization.StaffIdentity
import dev.deskseed.organization.StaffAuthorityCatalog
import dev.deskseed.organization.StaffIdentityService
import dev.deskseed.organization.StaffStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

@Service
internal class JpaStaffIdentityService(
    private val repository: StaffAccountRepository,
    private val authorityGrantRepository: StaffAuthorityGrantRepository,
    private val passwordEncoder: PasswordEncoder,
) : StaffIdentityService {
    private val dummyHash: String by lazy {
        requireNotNull(passwordEncoder.encode(UUID.randomUUID().toString()))
    }

    override fun authenticate(email: String, password: String): StaffIdentity? {
        val normalized = normalizeEmail(email)
        val account = repository.findByEmailNormalized(normalized)
        val passwordMatches = passwordEncoder.matches(password, account?.passwordHash ?: dummyHash)

        if (!passwordMatches || account == null || account.status != StaffStatus.ACTIVE) {
            return null
        }
        return account.toIdentity()
    }

    override fun findActiveById(id: UUID): StaffIdentity? = repository.findById(id)
        .filter { it.status == StaffStatus.ACTIVE }
        .map { it.toIdentity() }
        .orElse(null)

    override fun recordSuccessfulLogin(id: UUID, occurredAt: Instant) {
        val account = repository.findById(id).orElseThrow()
        account.lastLoginAt = occurredAt
        account.updatedAt = occurredAt
    }

    private fun normalizeEmail(value: String): String = value.trim().lowercase()

    private fun StaffAccountEntity.toIdentity(): StaffIdentity = StaffIdentity(
        id = id,
        email = emailDisplay,
        displayName = displayName,
        role = role,
        status = status,
        authorities = StaffAuthorityCatalog.forIdentity(
            role,
            authorityGrantRepository.findAllByStaffIdOrderByAuthorityAsc(id)
                .map(StaffAuthorityGrantEntity::authority)
                .toSet(),
        ),
    )
}
