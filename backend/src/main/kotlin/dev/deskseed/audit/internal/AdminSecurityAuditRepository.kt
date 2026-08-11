package dev.deskseed.audit.internal

import org.springframework.data.repository.Repository
import java.util.UUID

internal interface AdminSecurityAuditRepository : Repository<AdminSecurityAuditEventEntity, UUID> {
    fun saveAndFlush(entity: AdminSecurityAuditEventEntity): AdminSecurityAuditEventEntity
}
