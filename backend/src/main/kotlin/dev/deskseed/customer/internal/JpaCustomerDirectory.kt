package dev.deskseed.customer.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.customer.CustomerRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
internal class JpaCustomerDirectory(
    private val repository: CustomerRepository,
    private val clock: Clock,
) : CustomerDirectory {
    @Transactional
    override fun createUnverified(name: String, email: String): CustomerRef {
        val cleanName = name.trim()
        val displayEmail = email.trim()
        val normalizedEmail = displayEmail.lowercase(Locale.ROOT)
        val now = Instant.now(clock)

        val customer = repository.saveAndFlush(
            CustomerEntity(
                id = UUID.randomUUID(),
                name = cleanName,
                emailNormalized = normalizedEmail,
                emailDisplay = displayEmail,
                createdAt = now,
                updatedAt = now,
            ),
        )

        return CustomerRef(
            id = customer.id,
            name = customer.name,
            email = customer.emailDisplay,
        )
    }

    @Transactional(readOnly = true)
    override fun findById(customerId: UUID): CustomerRef? = repository.findById(customerId).orElse(null)?.let { customer ->
        CustomerRef(
            id = customer.id,
            name = customer.name,
            email = customer.emailDisplay,
            verifiedAt = customer.verifiedAt,
        )
    }

    @Transactional(readOnly = true)
    override fun existsByNormalizedEmail(email: String): Boolean =
        repository.existsByEmailNormalized(email.lowercase(Locale.ROOT))

    @Transactional(readOnly = true)
    override fun findVerifiedByNormalizedEmail(email: String): CustomerRef? =
        repository.findFirstByEmailNormalizedAndVerifiedAtIsNotNull(email.lowercase(Locale.ROOT))?.toRef()

    @Transactional
    override fun createVerified(name: String, email: String, verifiedAt: Instant): CustomerRef {
        val now = Instant.now(clock)
        return repository.saveAndFlush(
            CustomerEntity(
                id = UUID.randomUUID(),
                name = name.trim(),
                emailNormalized = email.lowercase(Locale.ROOT),
                emailDisplay = email.trim(),
                verifiedAt = verifiedAt,
                createdAt = now,
                updatedAt = now,
            ),
        ).toRef()
    }

    private fun CustomerEntity.toRef() = CustomerRef(
        id = id,
        name = name,
        email = emailDisplay,
        verifiedAt = verifiedAt,
    )
}
