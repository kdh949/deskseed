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

internal interface TicketRelationRepository : JpaRepository<TicketRelationEntity, UUID> {
    fun existsByTargetTicketIdAndRelationType(
        targetTicketId: UUID,
        relationType: TicketRelationType,
    ): Boolean

    @Query(
        value = """
            with recursive descendants(ticket_id) as (
                select target_ticket_id
                from ticket_relations
                where source_ticket_id = :targetTicketId
                  and relation_type = 'PARENT_CHILD'
                union
                select relation.target_ticket_id
                from ticket_relations relation
                join descendants on descendants.ticket_id = relation.source_ticket_id
                where relation.relation_type = 'PARENT_CHILD'
            )
            select exists(
                select 1 from descendants where ticket_id = :sourceTicketId
            )
        """,
        nativeQuery = true,
    )
    fun wouldCreateParentChildCycle(
        @Param("sourceTicketId") sourceTicketId: UUID,
        @Param("targetTicketId") targetTicketId: UUID,
    ): Boolean

    @Query(
        value = """
            select child.ticket_number
            from ticket_relations relation
            join tickets child on child.id = relation.target_ticket_id
            where relation.source_ticket_id = :parentTicketId
              and relation.relation_type = 'PARENT_CHILD'
              and child.status not in ('SOLVED', 'CLOSED')
            order by child.ticket_number
        """,
        nativeQuery = true,
    )
    fun findOpenChildTicketNumbers(@Param("parentTicketId") parentTicketId: UUID): List<Long>
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
