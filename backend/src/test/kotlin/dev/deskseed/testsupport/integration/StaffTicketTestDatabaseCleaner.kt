package dev.deskseed.testsupport.integration

import org.springframework.jdbc.core.JdbcTemplate

/**
 * Resets the mutable staff, customer, and ticket rows used by staff HTTP integration tests.
 *
 * This deliberately does not truncate every application table. Tests with additional state
 * (for example saved views or ticket configuration) remain responsible for that state.
 */
class StaffTicketTestDatabaseCleaner(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun resetMutableStaffTicketState() {
        jdbcTemplate.execute(
            """
            truncate table
                ai_knowledge_manifest_snapshots,
                ai_admin_operations,
                ai_reply_routing_cohorts,
                ai_feature_staff_allowlist,
                ai_activity_events,
                ai_feedback_idempotency,
                ai_request_feedback,
                ai_reply_sent_attributions,
                ai_reply_attribution_attempts,
                ai_reply_candidate_bindings,
                ai_result_access_audit_details,
                ai_context_access_audit_details,
                ai_knowledge_access_audit_details,
                ai_knowledge_index_outbox,
                ai_integration_outbox,
                ai_requests,
                staff_notifications,
                ticket_collaboration_note_mentions,
                ticket_collaboration_notes,
                access_audit_events,
                admin_security_audit_events,
                ticket_audit_events,
                ticket_audits,
                ticket_comments,
                request_access_tokens,
                tickets,
                customers,
                group_memberships,
                support_groups,
                staff_login_throttles,
                staff_accounts
            restart identity cascade
            """.trimIndent(),
        )
        jdbcTemplate.update(
            """
            update ai_settings
            set enabled = false, summary_enabled = false, triage_enabled = false,
                reply_draft_enabled = false, fast_model_alias = 'openai/gpt-5.6-luna',
                standard_model_alias = 'openai/gpt-5.6-terra',
                reply_routing_mode = 'STANDARD_ONLY', reply_routing_rollout_percent = 0,
                reply_routing_evaluation_approval_version = null, version = 0,
                updated_by_staff_id = null, updated_at = clock_timestamp()
            where singleton = true
            """.trimIndent(),
        )
    }
}
