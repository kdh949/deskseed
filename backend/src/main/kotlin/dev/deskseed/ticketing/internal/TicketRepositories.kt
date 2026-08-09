package dev.deskseed.ticketing.internal

import dev.deskseed.ticketing.CommentVisibility
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

internal interface TicketRepository : JpaRepository<TicketEntity, UUID> {
    fun findByTicketNumber(ticketNumber: Long): TicketEntity?
}

internal interface TicketCommentRepository : JpaRepository<TicketCommentEntity, UUID> {
    fun findAllByTicketIdAndVisibilityOrderByCreatedAtAscIdAsc(
        ticketId: UUID,
        visibility: CommentVisibility,
    ): List<TicketCommentEntity>
}

internal interface TicketAuditRepository : JpaRepository<TicketAuditEntity, UUID>

internal interface TicketAuditEventRepository : JpaRepository<TicketAuditEventEntity, UUID>
