package dev.deskseed.customer.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.customer.CustomerRef
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale

@Service
internal class JpaCustomerDirectory(
    private val repository: CustomerRepository,
    private val clock: Clock,
) : CustomerDirectory {
    @Transactional
    override fun findOrCreateUnverified(name: String, email: String): CustomerRef {
        val cleanName = name.trim()
        val displayEmail = email.trim()
        val normalizedEmail = displayEmail.lowercase(Locale.ROOT)
        val now = Instant.now(clock)

        val customer = repository.findByEmailNormalized(normalizedEmail)?.also {
            it.refreshUnverifiedProfile(cleanName, displayEmail, now)
        } ?: repository.save(
            CustomerEntity(
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
}
