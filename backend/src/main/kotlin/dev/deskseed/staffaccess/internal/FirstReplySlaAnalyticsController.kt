package dev.deskseed.staffaccess.internal

import dev.deskseed.sla.FirstReplySlaAnalytics
import dev.deskseed.sla.FirstReplySlaAnalyticsView
import dev.deskseed.ticketing.TicketPriority
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/analytics/first-reply-sla")
internal class FirstReplySlaAnalyticsController(
    private val analytics: FirstReplySlaAnalytics,
) {
    @GetMapping
    fun summary(
        @RequestParam(required = false) policyId: UUID?,
        @RequestParam(required = false) priority: TicketPriority?,
    ): FirstReplySlaAnalyticsView = analytics.summary(policyId, priority)
}
