alter table ai_cost_ledger drop constraint ai_cost_call_type_valid;
alter table ai_cost_ledger add constraint ai_cost_call_type_valid check (
    call_type in (
        'GENERATION', 'GENERATION_LOW_COST', 'GENERATION_ESCALATION',
        'CONTEXT_MEMORY', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING'
    )
);

alter table ai_provider_calls drop constraint ai_provider_call_stage_valid;
alter table ai_provider_calls add constraint ai_provider_call_stage_valid check (
    stage in (
        'GENERATION', 'GENERATION_LOW_COST', 'GENERATION_ESCALATION',
        'CONTEXT_MEMORY', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING'
    )
);

alter table ai_jobs
    add column reply_route_decision varchar(24) null,
    add column reply_route_cohort varchar(80) null,
    add column reply_route_policy_version varchar(40) null,
    add column reply_route_marker_version varchar(40) null,
    add column reply_route_rollout_percent integer null,
    add column reply_route_requested_alias varchar(120) null,
    add column reply_route_escalation_reason varchar(40) null,
    add constraint ai_job_reply_route_decision_valid check (
        reply_route_decision is null
        or reply_route_decision in ('STANDARD', 'LOW_COST', 'ESCALATED')
    ),
    add constraint ai_job_reply_route_rollout_valid check (
        reply_route_rollout_percent is null
        or reply_route_rollout_percent in (0, 10, 50, 100)
    ),
    add constraint ai_job_reply_route_alias_valid check (
        reply_route_requested_alias is null
        or reply_route_requested_alias ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,119}$'
    ),
    add constraint ai_job_reply_route_escalation_reason_valid check (
        reply_route_escalation_reason is null
        or reply_route_escalation_reason in (
            'PROVIDER_OUTPUT_INVALID', 'REPLY_VALIDATION_FAILED'
        )
    ),
    add constraint ai_job_reply_route_shape check (
        (
            reply_route_decision is null
            and reply_route_cohort is null
            and reply_route_policy_version is null
            and reply_route_marker_version is null
            and reply_route_rollout_percent is null
            and reply_route_requested_alias is null
            and reply_route_escalation_reason is null
        )
        or (
            reply_route_decision = 'STANDARD'
            and reply_route_cohort is null
            and reply_route_policy_version is not null
            and reply_route_marker_version is not null
            and reply_route_rollout_percent = 0
            and reply_route_requested_alias is not null
            and reply_route_escalation_reason is null
        )
        or (
            reply_route_decision = 'LOW_COST'
            and reply_route_cohort = 'reply-single-public-article-short-v1'
            and reply_route_policy_version is not null
            and reply_route_marker_version is not null
            and reply_route_rollout_percent in (10, 50, 100)
            and reply_route_requested_alias is not null
            and reply_route_escalation_reason is null
        )
        or (
            reply_route_decision = 'ESCALATED'
            and reply_route_cohort = 'reply-single-public-article-short-v1'
            and reply_route_policy_version is not null
            and reply_route_marker_version is not null
            and reply_route_rollout_percent in (10, 50, 100)
            and reply_route_requested_alias is not null
            and reply_route_escalation_reason is not null
        )
    );
