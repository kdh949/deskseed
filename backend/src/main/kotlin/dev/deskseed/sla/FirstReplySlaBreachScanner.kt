package dev.deskseed.sla

import java.time.Instant
import java.util.UUID

data class FirstReplySlaScanResult(
    val claimed: Int,
    val breached: Int,
    val checkpointDueAt: Instant?,
    val checkpointTargetId: UUID?,
)

interface FirstReplySlaBreachScanner {
    fun scan(owner: String, batchSize: Int): FirstReplySlaScanResult
}
