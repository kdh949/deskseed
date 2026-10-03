package dev.deskseed.aiassistance.internal

import dev.deskseed.aiassistance.AiFeature
import dev.deskseed.aiassistance.AiFeatureAvailability
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
internal class JdbcAiFeatureAvailability(private val jdbcTemplate: JdbcTemplate) : AiFeatureAvailability {
    override fun enabledFeatures(actorId: UUID): Set<AiFeature> = jdbcTemplate.query(
        """
        select settings.summary_enabled, settings.triage_enabled, settings.reply_draft_enabled
        from ai_settings settings
        where settings.singleton = true and settings.enabled = true
          and exists (
              select 1 from ai_feature_staff_allowlist allowed
              join staff_accounts staff on staff.id = allowed.staff_id and staff.status = 'ACTIVE'
              where allowed.staff_id = ?
          )
        """.trimIndent(),
        { row, _ ->
            buildSet {
                if (row.getBoolean("summary_enabled")) add(AiFeature.TICKET_SUMMARY)
                if (row.getBoolean("triage_enabled")) add(AiFeature.TICKET_TRIAGE)
                if (row.getBoolean("reply_draft_enabled")) add(AiFeature.TICKET_REPLY_DRAFT)
            }
        },
        actorId,
    ).singleOrNull().orEmpty()
}
