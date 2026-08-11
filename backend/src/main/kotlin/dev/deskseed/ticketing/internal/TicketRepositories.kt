package dev.deskseed.ticketing.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.Repository
import java.util.UUID

internal interface TicketRepository : JpaRepository<TicketEntity, UUID> {
    fun findByTicketNumber(ticketNumber: Long): TicketEntity?

    fun existsByAssigneeId(assigneeId: UUID): Boolean

    fun existsByGroupId(groupId: UUID): Boolean

    fun existsByGroupIdAndAssigneeId(groupId: UUID, assigneeId: UUID): Boolean
}

internal interface TicketCommentRepository : JpaRepository<TicketCommentEntity, UUID> {
    fun saveAndFlush(entity: TicketCommentEntity): TicketCommentEntity
}

internal interface TicketAuditRepository : Repository<TicketAuditEntity, UUID> {
    fun saveAndFlush(entity: TicketAuditEntity): TicketAuditEntity

    @Query(
        value = """
            select distinct event.field_name
            from ticket_audit_events event
            join ticket_audits audit on audit.id = event.audit_id
            where audit.ticket_id = :ticketId
              and audit.ticket_version > :expectedVersion
              and event.field_name in (:fieldNames)
        """,
        nativeQuery = true,
    )
    fun findConflictingFields(
        @Param("ticketId") ticketId: UUID,
        @Param("expectedVersion") expectedVersion: Long,
        @Param("fieldNames") fieldNames: Collection<String>,
    ): List<String>
}

internal interface TicketAuditEventRepository : Repository<TicketAuditEventEntity, UUID> {
    fun saveAllAndFlush(entities: Iterable<TicketAuditEventEntity>): List<TicketAuditEventEntity>
}
