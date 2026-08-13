package dev.deskseed.customer.internal

import dev.deskseed.customer.CustomerDirectory
import dev.deskseed.customer.CustomerRef
import dev.deskseed.customer.CustomerSearchResult
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

@Service
internal class JpaCustomerDirectory(
    private val repository: CustomerRepository,
    private val jdbcTemplate: NamedParameterJdbcTemplate,
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

    @Transactional(readOnly = true)
    override fun search(query: String, limit: Int): CustomerSearchResult {
        require(query.isNotBlank()) { "Search query is required" }
        require(limit in 1..25) { "Search limit must be between 1 and 25" }

        // ILIKE with a leading/trailing wildcard is sargable against the pg_trgm GIN indexes
        // added in V27; email_normalized is already lowercased at write time (createUnverified/
        // createVerified), so no extra lower() is needed on either side of the comparison.
        val likePattern = "%${escapeLikeWildcards(query.trim())}%"
        val whereClause = "name ilike :likePattern escape '\\' or email_normalized ilike :likePattern escape '\\'"
        val parameters = MapSqlParameterSource()
            .addValue("likePattern", likePattern)
            .addValue("limit", limit)

        val resultCount = jdbcTemplate.queryForObject(
            "select count(*) from customers where $whereClause",
            parameters,
            Long::class.java,
        ) ?: 0L
        val items = jdbcTemplate.query(
            """
            select id, name, email_normalized, email_display, verified_at
            from customers
            where $whereClause
            order by name asc, id asc
            limit :limit
            """.trimIndent(),
            parameters,
        ) { resultSet, _ ->
            CustomerRef(
                id = resultSet.getObject("id", UUID::class.java),
                name = resultSet.getString("name"),
                email = resultSet.getString("email_display"),
                verifiedAt = resultSet.getTimestamp("verified_at")?.toInstant(),
            )
        }
        return CustomerSearchResult(items, resultCount)
    }

    private fun escapeLikeWildcards(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    private fun CustomerEntity.toRef() = CustomerRef(
        id = id,
        name = name,
        email = emailDisplay,
        verifiedAt = verifiedAt,
    )
}
