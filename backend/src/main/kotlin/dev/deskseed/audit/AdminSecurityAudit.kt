package dev.deskseed.audit

import dev.deskseed.foundation.ActorType
import dev.deskseed.foundation.RequestSource
import java.time.Instant
import java.util.UUID

enum class AdminSecurityOutcome {
    SUCCEEDED,
    DENIED,
    FAILED,
}

data class AdminSecurityAudit(
    val eventType: String,
    val actorType: ActorType,
    val actorId: UUID?,
    val actorDisplaySnapshot: String?,
    val source: RequestSource,
    val targetType: String,
    val targetId: UUID?,
    val outcome: AdminSecurityOutcome,
    val requestId: String,
    val correlationId: String,
    val metadata: Map<String, String> = emptyMap(),
    val occurredAt: Instant,
)

interface AdminSecurityAuditWriter {
    fun append(event: AdminSecurityAudit): UUID
}
