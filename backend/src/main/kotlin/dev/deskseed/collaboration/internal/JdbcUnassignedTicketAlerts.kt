package dev.deskseed.collaboration.internal

import dev.deskseed.collaboration.UnassignedTicketAlerts
import dev.deskseed.collaboration.StaffNotificationCreated
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
internal class JdbcUnassignedTicketAlerts(private val jdbc: JdbcTemplate, private val publisher: ApplicationEventPublisher) : UnassignedTicketAlerts {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(ticketId: UUID, groupId: UUID, triggerId: UUID, triggerVersion: Int, executionId: UUID, occurredAt: Instant) {
        val notifications = jdbc.query(
            """insert into staff_notifications (id,recipient_staff_id,notification_type,ticket_id,note_id,created_at,read_at,trigger_id,trigger_version,trigger_execution_id)
                select gen_random_uuid(), membership.staff_id, 'UNASSIGNED_TICKET_ALERT', ticket.id, null, ?, null, ?, ?, ?
                from group_memberships membership
                join staff_accounts staff on staff.id = membership.staff_id and staff.status = 'ACTIVE' and staff.role in ('ADMIN','AGENT')
                join support_groups support on support.id = membership.group_id and support.status = 'ACTIVE'
                join tickets ticket on ticket.id = ? and ticket.group_id = support.id and ticket.assignee_id is null
                where membership.group_id = ? and membership.status = 'ACTIVE'
                on conflict (recipient_staff_id, trigger_execution_id) where trigger_execution_id is not null do nothing
                returning id, recipient_staff_id""",
            { rs, _ -> StaffNotificationCreated(rs.getObject("recipient_staff_id", UUID::class.java), rs.getObject("id", UUID::class.java), occurredAt) },
            Timestamp.from(occurredAt), triggerId, triggerVersion, executionId, ticketId, groupId,
        )
        notifications.forEach(publisher::publishEvent)
    }
}
