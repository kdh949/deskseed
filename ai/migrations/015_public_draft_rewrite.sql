alter table ai_jobs
    add column source_job_id uuid null,
    drop constraint ai_job_feature_valid,
    add constraint ai_job_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft', 'ticket.reply_rewrite')
    ),
    drop constraint ai_job_input_revision_shape,
    add constraint ai_job_input_revision_shape check (
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
    add constraint ai_job_rewrite_source_shape check (
        (
            feature = 'ticket.reply_rewrite'
            and source_job_id is not null
            and generation_mode = 'REUSE_OR_CREATE'
            and candidate_id is null
            and candidate_sequence is null
            and input_scope = 'PUBLIC_DRAFT_ONLY'
        )
        or (
            feature <> 'ticket.reply_rewrite'
            and source_job_id is null
            and input_scope = 'PUBLIC_ONLY'
        )
    );

create index ai_jobs_source_job_idx
    on ai_jobs (source_job_id, created_at desc)
    where source_job_id is not null;

alter table ai_cost_ledger
    alter column call_type type varchar(40),
    drop constraint ai_cost_call_type_valid,
    add constraint ai_cost_call_type_valid check (
        call_type in (
            'GENERATION', 'GENERATION_LOW_COST', 'GENERATION_ESCALATION',
            'GENERATION_REWRITE', 'GENERATION_REWRITE_VALIDATION',
            'CONTEXT_MEMORY', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING'
        )
    );

alter table ai_provider_calls
    alter column stage type varchar(40),
    drop constraint ai_provider_call_stage_valid,
    add constraint ai_provider_call_stage_valid check (
        stage in (
            'GENERATION', 'GENERATION_LOW_COST', 'GENERATION_ESCALATION',
            'GENERATION_REWRITE', 'GENERATION_REWRITE_VALIDATION',
            'CONTEXT_MEMORY', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING'
        )
    );
