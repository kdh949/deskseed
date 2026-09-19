create table ai_reply_candidate_bindings (
    job_id uuid not null references ai_requests(job_id) on delete cascade,
    candidate_id uuid not null,
    requester_staff_id uuid not null references staff_accounts(id),
    ticket_id uuid not null references tickets(id),
    feature varchar(32) not null,
    answer_sha256 char(64) not null,
    answer_code_point_length integer not null,
    contract_version varchar(32) not null,
    result_expires_at timestamptz not null,
    bound_at timestamptz not null,
    last_authorized_at timestamptz not null,
    primary key (job_id, candidate_id),
    constraint ai_reply_candidate_binding_feature_valid check (
        feature in ('ticket.reply_draft', 'ticket.reply_rewrite')
    ),
    constraint ai_reply_candidate_binding_digest_valid check (answer_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ai_reply_candidate_binding_length_valid check (answer_code_point_length between 1 and 6000),
    constraint ai_reply_candidate_binding_contract_bounded check (
        length(btrim(contract_version)) between 1 and 32 and contract_version !~ '[[:cntrl:]]'
    ),
    constraint ai_reply_candidate_binding_expiry_valid check (result_expires_at > bound_at)
);

create index ai_reply_candidate_bindings_lookup_idx
    on ai_reply_candidate_bindings (requester_staff_id, ticket_id, candidate_id, result_expires_at);

create table ai_reply_attribution_attempts (
    comment_id uuid primary key references ticket_comments(id) on delete cascade,
    ticket_id uuid not null references tickets(id),
    requester_staff_id uuid not null references staff_accounts(id),
    client_state varchar(32) not null,
    outcome varchar(48) not null,
    source_count integer not null,
    contract_version varchar(32) not null,
    created_at timestamptz not null,
    constraint ai_reply_attribution_client_state_valid check (
        client_state in ('NO_AI_LINEAGE', 'LINEAGE_PRESENT', 'LINEAGE_LOST')
    ),
    constraint ai_reply_attribution_outcome_valid check (
        outcome in (
            'NO_AI_LINEAGE', 'ATTRIBUTED_SINGLE', 'ATTRIBUTED_MULTI',
            'UNATTRIBUTED_LINEAGE_LOST', 'UNATTRIBUTED_VALIDATION_FAILED', 'IGNORED_INTERNAL'
        )
    ),
    constraint ai_reply_attribution_source_count_valid check (source_count between 0 and 4),
    constraint ai_reply_attribution_contract_bounded check (
        length(btrim(contract_version)) between 1 and 32 and contract_version !~ '[[:cntrl:]]'
    )
);

create index ai_reply_attribution_attempts_retention_idx
    on ai_reply_attribution_attempts (created_at, comment_id);

create table ai_reply_sent_attributions (
    comment_id uuid not null references ai_reply_attribution_attempts(comment_id) on delete cascade,
    candidate_id uuid not null,
    job_id uuid not null references ai_requests(job_id),
    source_ordinal integer not null,
    attribution_kind varchar(24) not null,
    source_count integer not null,
    original_length integer null,
    final_length integer null,
    edit_distance integer null,
    inserted_length integer null,
    deleted_length integer null,
    edit_ratio numeric(9, 8) null,
    created_at timestamptz not null,
    primary key (comment_id, candidate_id),
    constraint ai_reply_sent_source_ordinal_unique unique (comment_id, source_ordinal),
    constraint ai_reply_sent_attribution_kind_valid check (attribution_kind in ('SINGLE_SOURCE', 'MULTI_SOURCE')),
    constraint ai_reply_sent_source_shape check (source_count between 1 and 4 and source_ordinal between 1 and source_count),
    constraint ai_reply_sent_edit_shape check (
        (
            attribution_kind = 'SINGLE_SOURCE'
            and source_count = 1 and source_ordinal = 1
            and original_length is not null and final_length is not null and edit_distance is not null
            and inserted_length is not null and deleted_length is not null and edit_ratio is not null
            and original_length between 1 and 6000 and final_length between 0 and 20000
            and edit_distance >= 0 and inserted_length >= 0 and deleted_length >= 0
            and edit_ratio between 0 and 1
        ) or (
            attribution_kind = 'MULTI_SOURCE'
            and source_count between 2 and 4
            and original_length is null and final_length is null and edit_distance is null
            and inserted_length is null and deleted_length is null and edit_ratio is null
        )
    )
);

create index ai_reply_sent_candidate_first_use_idx
    on ai_reply_sent_attributions (candidate_id, created_at, comment_id);

alter table ai_integration_outbox
    drop constraint ai_outbox_job_event_revision_unique,
    drop constraint ai_outbox_event_type_valid,
    add column usage_comment_id uuid null references ticket_comments(id),
    add column usage_candidate_id uuid null,
    add constraint ai_outbox_event_type_valid check (
        event_type in ('JOB_REQUESTED', 'JOB_CANCELLED', 'JOB_FEEDBACK', 'REPLY_SENT')
    ),
    add constraint ai_outbox_usage_shape check (
        (event_type = 'REPLY_SENT' and usage_comment_id is not null and usage_candidate_id is not null)
        or (event_type <> 'REPLY_SENT' and usage_comment_id is null and usage_candidate_id is null)
    );

create unique index ai_outbox_job_event_revision_unique
    on ai_integration_outbox (job_id, event_type, request_revision)
    where event_type <> 'REPLY_SENT';

create unique index ai_outbox_reply_sent_unique
    on ai_integration_outbox (usage_comment_id, usage_candidate_id)
    where event_type = 'REPLY_SENT';

create index ai_reply_candidate_bindings_retention_idx
    on ai_reply_candidate_bindings (last_authorized_at, job_id, candidate_id);
