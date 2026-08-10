package dev.deskseed.customer.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

internal interface CustomerRepository : JpaRepository<CustomerEntity, UUID> {
    @Query(
        value = """
            insert into customers (
                id,
                name,
                email_normalized,
                email_display,
                created_at,
                updated_at
            )
            values (
                :id,
                :name,
                :emailNormalized,
                :emailDisplay,
                :now,
                :now
            )
            on conflict (email_normalized) do update
            set
                name = case
                    when customers.verified_at is null then excluded.name
                    else customers.name
                end,
                email_display = case
                    when customers.verified_at is null then excluded.email_display
                    else customers.email_display
                end,
                updated_at = case
                    when customers.verified_at is null then excluded.updated_at
                    else customers.updated_at
                end
            returning
                id,
                name,
                email_display as emailDisplay
            """,
        nativeQuery = true,
    )
    fun upsertUnverified(
        @Param("id") id: UUID,
        @Param("name") name: String,
        @Param("emailNormalized") emailNormalized: String,
        @Param("emailDisplay") emailDisplay: String,
        @Param("now") now: java.time.Instant,
    ): CustomerUpsertRow
}

internal interface CustomerUpsertRow {
    val id: UUID
    val name: String
    val emailDisplay: String
}
