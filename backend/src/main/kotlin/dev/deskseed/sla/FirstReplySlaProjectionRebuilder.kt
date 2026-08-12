package dev.deskseed.sla

import java.util.UUID

data class FirstReplySlaProjectionRebuildResult(
    val ticketId: UUID,
    val intervalCount: Int,
    val targetRecalculated: Boolean,
)

interface FirstReplySlaProjectionRebuilder {
    /** Replays canonical ticket audits into the derived status intervals and active SLA clock. */
    fun rebuild(ticketId: UUID): FirstReplySlaProjectionRebuildResult
}
