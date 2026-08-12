package dev.deskseed.sla.internal

import dev.deskseed.sla.FirstReplySlaAnalytics
import dev.deskseed.sla.FirstReplySlaAnalyticsView
import dev.deskseed.ticketing.TicketPriority
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.sql.Timestamp
import java.util.UUID

@Service
internal class JdbcFirstReplySlaAnalytics(
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) : FirstReplySlaAnalytics {
    @Transactional(readOnly = true)
    override fun summary(policyId: UUID?, priority: TicketPriority?): FirstReplySlaAnalyticsView {
        val conditions = mutableListOf<String>()
        val parameters = MapSqlParameterSource("now", Timestamp.from(clock.instant()))
        policyId?.let {
            conditions += "fact.policy_id = :policyId"
            parameters.addValue("policyId", it)
        }
        priority?.let {
            conditions += "fact.priority_snapshot = :priority"
            parameters.addValue("priority", it.name)
        }
        val where = conditions.takeIf { it.isNotEmpty() }?.joinToString(" and ", "where ").orEmpty()
        val counts = jdbc.queryForMap(
            """
            with effective as (
                select case
                    when fact.outcome = 'ACTIVE' and fact.due_at <= :now then 'BREACHED'
                    else fact.outcome
                end as outcome
                from analytics_first_reply_facts fact
                $where
            )
            select
                count(*) filter (where outcome = 'ACTIVE') as active,
                count(*) filter (where outcome = 'PAUSED') as paused,
                count(*) filter (where outcome = 'ACHIEVED') as achieved,
                count(*) filter (where outcome = 'BREACHED') as breached,
                count(*) filter (where outcome = 'CANCELLED') as cancelled,
                count(*) filter (where outcome = 'NO_POLICY') as no_policy
            from effective
            """.trimIndent(),
            parameters,
        )
        fun count(key: String) = (counts[key] as Number).toLong()
        return FirstReplySlaAnalyticsView(
            calculationVersion = FirstReplySlaLifecycleProjection.CALCULATION_VERSION,
            active = count("active"),
            paused = count("paused"),
            achieved = count("achieved"),
            breached = count("breached"),
            cancelled = count("cancelled"),
            noPolicy = count("no_policy"),
        )
    }
}
