alter table ai_settings
    add column reply_routing_mode varchar(32) not null default 'STANDARD_ONLY',
    add column reply_routing_rollout_percent integer not null default 0,
    add column reply_routing_evaluation_approval_version varchar(80) null,
    add constraint ai_settings_reply_routing_mode_valid check (
        reply_routing_mode in ('STANDARD_ONLY', 'EVALUATED_COHORT')
    ),
    add constraint ai_settings_reply_routing_rollout_valid check (
        reply_routing_rollout_percent in (0, 10, 50, 100)
    ),
    add constraint ai_settings_reply_routing_approval_valid check (
        reply_routing_evaluation_approval_version is null
        or reply_routing_evaluation_approval_version ~ '^[a-z0-9][a-z0-9._-]{0,79}$'
    ),
    add constraint ai_settings_reply_routing_shape check (
        (
            reply_routing_mode = 'STANDARD_ONLY'
            and reply_routing_rollout_percent = 0
            and reply_routing_evaluation_approval_version is null
        )
        or (
            reply_routing_mode = 'EVALUATED_COHORT'
            and reply_routing_rollout_percent in (10, 50, 100)
            and reply_routing_evaluation_approval_version is not null
        )
    );

create table ai_reply_routing_cohorts (
    cohort_key varchar(80) primary key,
    added_by_staff_id uuid not null references staff_accounts(id),
    added_at timestamptz not null,
    constraint ai_reply_routing_cohort_key_valid check (
        cohort_key = 'reply-single-public-article-short-v1'
    )
);
