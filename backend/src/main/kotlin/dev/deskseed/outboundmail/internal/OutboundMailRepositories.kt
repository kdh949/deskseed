package dev.deskseed.outboundmail.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

internal interface OutboundMailIntentRepository : JpaRepository<OutboundMailIntentEntity, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): OutboundMailIntentEntity?

    @Query(
        // PostgreSQL documents SKIP LOCKED as appropriate for multiple consumers of a queue-like table.
        // Source: https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE
        value = """
            select *
            from outbound_mail_intents
            where (
                (status in ('QUEUED', 'RETRY_WAIT') and next_attempt_at <= :now and attempt_count < max_attempts)
                or (status = 'SENDING' and lease_expires_at <= :now)
            )
            order by coalesce(next_attempt_at, lease_expires_at), queued_at, id
            for update skip locked
            limit 1
        """,
        nativeQuery = true,
    )
    fun lockNextDue(@Param("now") now: Instant): OutboundMailIntentEntity?

    @Query("select intent from OutboundMailIntentEntity intent where intent.id = :id")
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    fun lockById(@Param("id") id: UUID): OutboundMailIntentEntity?

    fun countByStatusIn(statuses: Collection<MailIntentStatus>): Long
}

internal interface OutboundMailAttemptRepository : JpaRepository<OutboundMailAttemptEntity, UUID> {
    fun findFirstByIntentIdAndStatusOrderByAttemptNumberDesc(
        intentId: UUID,
        status: MailAttemptStatus,
    ): OutboundMailAttemptEntity?
}

internal interface OutboundMailDeliveryEventRepository : JpaRepository<OutboundMailDeliveryEventEntity, UUID>
