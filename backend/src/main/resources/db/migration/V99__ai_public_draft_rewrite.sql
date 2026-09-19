alter table ai_settings
    add column reply_rewrite_enabled boolean not null default false;

alter table ai_requests
    add column source_job_id uuid null references ai_requests(job_id),
    drop constraint ai_request_feature_valid,
    add constraint ai_request_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft', 'ticket.reply_rewrite')
    ),
    drop constraint ai_request_input_revision_shape,
    add constraint ai_request_input_revision_shape check (
        (ai_input_revision is null and input_policy_version is null)
        or (
            ai_input_revision is not null
            and input_policy_version is not null
            and ai_input_revision ~ '^[0-9a-f]{64}$'
            and (
                (feature = 'ticket.summary' and input_policy_version = 'summary-input-v1')
                or (feature = 'ticket.triage' and input_policy_version = 'triage-input-v1')
                or (feature = 'ticket.reply_draft' and input_policy_version = 'reply-input-v1')
                or (feature = 'ticket.reply_rewrite' and input_policy_version = 'rewrite-input-v1')
            )
        )
    ),
    add constraint ai_request_rewrite_source_shape check (
        (
            feature = 'ticket.reply_rewrite'
            and source_job_id is not null
            and generation_mode = 'REUSE_OR_CREATE'
            and candidate_id is null
            and candidate_sequence is null
        )
        or (feature <> 'ticket.reply_rewrite' and source_job_id is null)
    );

create index ai_requests_source_job_idx
    on ai_requests (source_job_id, created_at desc)
    where source_job_id is not null;

alter table ai_result_access_audit_details
    drop constraint ai_result_access_feature_valid,
    add constraint ai_result_access_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft', 'ticket.reply_rewrite')
    );
