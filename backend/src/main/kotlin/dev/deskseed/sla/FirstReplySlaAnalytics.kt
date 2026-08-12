package dev.deskseed.sla

import dev.deskseed.ticketing.TicketPriority
import java.util.UUID

data class FirstReplySlaAnalyticsView(
    val metric: String = "FIRST_REPLY",
    val calculationVersion: String,
    val active: Long,
    val paused: Long,
    val achieved: Long,
    val breached: Long,
    val cancelled: Long,
    val noPolicy: Long,
) {
    val achievedRateDenominator: Long = achieved + breached
    val achievedRate: Double? = achievedRateDenominator.takeIf { it > 0 }?.let { achieved.toDouble() / it }
}

interface FirstReplySlaAnalytics {
    fun summary(policyId: UUID?, priority: TicketPriority?): FirstReplySlaAnalyticsView
}
