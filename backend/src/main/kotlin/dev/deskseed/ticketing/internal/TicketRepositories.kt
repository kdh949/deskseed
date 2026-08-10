package dev.deskseed.ticketing.internal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.Repository
import java.util.UUID

internal interface TicketRepository : JpaRepository<TicketEntity, UUID> {
    fun findByTicketNumber(ticketNumber: Long): TicketEntity?
}

internal interface TicketCommentRepository : JpaRepository<TicketCommentEntity, UUID>

internal interface TicketAuditRepository : Repository<TicketAuditEntity, UUID> {
    fun saveAndFlush(entity: TicketAuditEntity): TicketAuditEntity
}

internal interface TicketAuditEventRepository : Repository<TicketAuditEventEntity, UUID> {
    fun saveAllAndFlush(entities: Iterable<TicketAuditEventEntity>): List<TicketAuditEventEntity>
}
