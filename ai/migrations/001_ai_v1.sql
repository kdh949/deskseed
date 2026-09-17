create extension if not exists vector;

create table if not exists ai_schema_history (
    version integer primary key,
    description varchar(200) not null,
    checksum char(64) not null,
    applied_at timestamptz not null default clock_timestamp()
);

create table ai_job_inbox (
    event_id uuid primary key,
    job_id uuid not null,
    event_type varchar(24) not null,
    request_fingerprint char(64) not null,
    received_at timestamptz not null,
    constraint ai_job_inbox_event_type_valid check (event_type in ('JOB_REQUESTED', 'JOB_CANCELLED')),
    constraint ai_job_inbox_fingerprint_shape check (request_fingerprint ~ '^[0-9a-f]{64}$')
);
create unique index ai_job_inbox_job_request_unique on ai_job_inbox (job_id, request_fingerprint);

create table ai_cancellation_tombstones (
    job_id uuid primary key,
    workspace_key varchar(80) not null,
    request_revision bigint not null,
    event_id uuid not null unique references ai_job_inbox(event_id),
    request_fingerprint char(64) not null,
    cancelled_at timestamptz not null,
    constraint ai_cancellation_tombstone_revision_valid check (request_revision > 1),
    constraint ai_cancellation_tombstone_fingerprint_shape check (request_fingerprint ~ '^[0-9a-f]{64}$')
);

create table ai_jobs (
    job_id uuid primary key,
    workspace_key varchar(80) not null,
    requester_id uuid not null,
    ticket_id uuid not null,
    ticket_number bigint not null,
    feature varchar(32) not null,
    status varchar(24) not null,
    phase varchar(24) not null,
    generation integer not null default 1,
    lease_epoch bigint not null default 0,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    request_revision bigint not null,
    cancel_requested boolean not null default false,
    context_revision char(64) not null,
    context_policy_version varchar(32) not null,
    input_scope varchar(32) not null,
    options_json jsonb not null,
    request_fingerprint char(64) not null,
    traceparent varchar(512) null,
    tracestate varchar(512) null,
    attempt_count integer not null default 0,
    error_code varchar(80) null,
    result_schema_version integer null,
    result_ciphertext bytea null,
    result_nonce bytea null,
    result_expires_at timestamptz null,
    model_alias varchar(100) null,
    actual_model varchar(160) null,
    prompt_version varchar(80) null,
    config_version varchar(80) null,
    source_comment_ids uuid[] null,
    generated_at timestamptz null,
    cost_microusd bigint null,
    created_at timestamptz not null,
    deadline_at timestamptz not null,
    started_at timestamptz null,
    completed_at timestamptz null,
    updated_at timestamptz not null,
    constraint ai_job_feature_valid check (feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft')),
    constraint ai_job_status_valid check (status in (
        'QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED', 'NEEDS_REVIEW',
        'FAILED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED'
    )),
    constraint ai_job_phase_valid check (phase in ('QUEUED', 'AUTHORIZE', 'RETRIEVE', 'GENERATE', 'VALIDATE', 'COMPLETE')),
    constraint ai_job_generation_positive check (generation > 0 and lease_epoch >= 0 and request_revision > 0),
    constraint ai_job_attempt_nonnegative check (attempt_count >= 0),
    constraint ai_job_options_object check (jsonb_typeof(options_json) = 'object'),
    constraint ai_job_fingerprint_shape check (
        context_revision ~ '^[0-9a-f]{64}$' and request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    constraint ai_job_deadline_valid check (deadline_at > created_at),
    constraint ai_job_lease_shape check (
        (status = 'RUNNING' and lease_owner is not null and lease_expires_at is not null)
        or status <> 'RUNNING'
    ),
    constraint ai_job_result_shape check (
        (result_ciphertext is null and result_nonce is null and result_schema_version is null
            and actual_model is null and generated_at is null)
        or (result_ciphertext is not null and result_nonce is not null and result_schema_version is not null
            and model_alias is not null and actual_model is not null and prompt_version is not null
            and config_version is not null and source_comment_ids is not null and generated_at is not null)
    ),
    constraint ai_job_cost_nonnegative check (cost_microusd is null or cost_microusd >= 0)
);
create index ai_jobs_status_dispatch_idx on ai_jobs (status, deadline_at, created_at, job_id);
create index ai_jobs_requester_ticket_idx on ai_jobs (requester_id, ticket_id, created_at desc, job_id desc);
create index ai_jobs_lease_recovery_idx on ai_jobs (lease_expires_at, job_id) where status = 'RUNNING';
create index ai_jobs_result_retention_idx on ai_jobs (result_expires_at, job_id) where result_ciphertext is not null;

create table ai_dispatch_outbox (
    event_id uuid primary key,
    job_id uuid not null references ai_jobs(job_id),
    generation integer not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    delivered_at timestamptz null,
    created_at timestamptz not null,
    last_error_code varchar(80) null,
    constraint ai_dispatch_generation_positive check (generation > 0),
    constraint ai_dispatch_status_valid check (status in ('PENDING', 'LEASED', 'DELIVERED', 'DEAD')),
    constraint ai_dispatch_attempts_nonnegative check (attempts >= 0),
    constraint ai_dispatch_job_generation_unique unique (job_id, generation)
);
create index ai_dispatch_pending_idx on ai_dispatch_outbox (available_at, created_at, event_id) where status = 'PENDING';
create index ai_dispatch_lease_idx on ai_dispatch_outbox (lease_expires_at, event_id) where status = 'LEASED';

create table ai_cost_ledger (
    reservation_id uuid primary key,
    operation_key varchar(200) not null unique,
    job_id uuid null,
    workspace_key varchar(80) not null,
    requester_id uuid null,
    budget_bucket varchar(16) not null,
    call_type varchar(24) not null,
    budget_date date not null,
    status varchar(20) not null,
    reserved_microusd bigint not null,
    settled_microusd bigint null,
    pricing_version varchar(40) not null,
    model_alias varchar(100) not null,
    created_at timestamptz not null,
    settled_at timestamptz null,
    unknown_since timestamptz null,
    constraint ai_cost_status_valid check (status in ('RESERVED', 'SETTLED', 'RELEASED', 'UNKNOWN')),
    constraint ai_cost_bucket_valid check (budget_bucket in ('ACTOR', 'SYSTEM')),
    constraint ai_cost_call_type_valid check (call_type in ('GENERATION', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING')),
    constraint ai_cost_actor_shape check (
        (budget_bucket = 'ACTOR' and requester_id is not null and job_id is not null)
        or (budget_bucket = 'SYSTEM' and requester_id is null)
    ),
    constraint ai_cost_values_valid check (
        reserved_microusd > 0 and (settled_microusd is null or settled_microusd >= 0)
    )
);
create index ai_cost_workspace_day_idx on ai_cost_ledger (workspace_key, budget_date, status);
create index ai_cost_actor_day_idx on ai_cost_ledger (workspace_key, requester_id, budget_date, status);

create table ai_kb_index_inbox (
    event_id uuid primary key,
    article_id uuid not null,
    revision_id uuid not null,
    event_fingerprint char(64) not null,
    received_at timestamptz not null
);

create table ai_kb_index_jobs (
    event_id uuid primary key references ai_kb_index_inbox(event_id),
    workspace_key varchar(80) not null,
    article_id uuid not null,
    revision_id uuid not null,
    action varchar(16) not null,
    public_revision char(64) not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    created_at timestamptz not null,
    completed_at timestamptz null,
    last_error_code varchar(80) null,
    constraint ai_kb_index_job_action_valid check (action in ('UPSERT', 'DELETE')),
    constraint ai_kb_index_job_status_valid check (status in ('PENDING', 'LEASED', 'SUCCEEDED', 'DEAD')),
    constraint ai_kb_index_job_revision_shape check (public_revision ~ '^[0-9a-f]{64}$'),
    constraint ai_kb_index_job_lease_shape check (
        (status = 'LEASED' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'LEASED' and lease_owner is null and lease_expires_at is null)
    )
);
create index ai_kb_index_jobs_due_idx on ai_kb_index_jobs (available_at, created_at, event_id)
    where status = 'PENDING';
create index ai_kb_index_jobs_lease_idx on ai_kb_index_jobs (lease_expires_at, event_id)
    where status = 'LEASED';

create table ai_kb_revisions (
    article_id uuid not null,
    revision_id uuid not null,
    workspace_key varchar(80) not null,
    slug varchar(180) not null,
    title varchar(300) not null,
    public_revision char(64) not null,
    status varchar(20) not null,
    indexed_at timestamptz not null,
    deleted_at timestamptz null,
    primary key (article_id, revision_id),
    constraint ai_kb_revision_status_valid check (status in ('PUBLIC', 'DELETED')),
    constraint ai_kb_revision_public_hash check (public_revision ~ '^[0-9a-f]{64}$')
);
create index ai_kb_current_idx on ai_kb_revisions (workspace_key, article_id, indexed_at desc);

create table ai_kb_chunks (
    chunk_id uuid primary key,
    article_id uuid not null,
    revision_id uuid not null,
    workspace_key varchar(80) not null,
    ordinal integer not null,
    content text not null,
    content_sha256 char(64) not null,
    embedding vector(1536) not null,
    indexed_at timestamptz not null,
    foreign key (article_id, revision_id) references ai_kb_revisions(article_id, revision_id) on delete cascade,
    constraint ai_kb_chunk_ordinal_nonnegative check (ordinal >= 0),
    constraint ai_kb_chunk_content_bounded check (length(content) between 1 and 8000),
    constraint ai_kb_chunk_hash_shape check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ai_kb_chunk_revision_ordinal_unique unique (article_id, revision_id, ordinal)
);
create index ai_kb_chunks_embedding_hnsw on ai_kb_chunks using hnsw (embedding vector_cosine_ops);
create index ai_kb_chunks_content_fts_idx on ai_kb_chunks using gin (to_tsvector('simple', content));
create index ai_kb_chunks_workspace_revision_idx on ai_kb_chunks (workspace_key, article_id, revision_id, ordinal);

create table ai_feedback_inbox (
    event_id uuid primary key,
    job_id uuid not null,
    event_fingerprint char(64) not null,
    received_at timestamptz not null,
    constraint ai_feedback_inbox_fingerprint_shape check (event_fingerprint ~ '^[0-9a-f]{64}$')
);

create table ai_feedback (
    job_id uuid not null references ai_jobs(job_id),
    requester_id uuid not null,
    feedback_type varchar(24) not null,
    source_revision bigint not null,
    last_event_id uuid not null unique references ai_feedback_inbox(event_id),
    reason_code varchar(40) null,
    score_id uuid not null unique,
    score_name varchar(80) not null,
    first_recorded_at timestamptz not null,
    updated_at timestamptz not null,
    exported_revision bigint not null default 0,
    next_export_at timestamptz null,
    last_export_error varchar(80) null,
    export_lease_owner varchar(100) null,
    export_lease_expires_at timestamptz null,
    primary key (job_id, feedback_type),
    constraint ai_feedback_type_valid check (
        feedback_type in ('helpful', 'unhelpful', 'inserted', 'edited')
    ),
    constraint ai_feedback_revision_valid check (
        source_revision > 0 and exported_revision >= 0 and exported_revision <= source_revision
    ),
    constraint ai_feedback_reason_bounded check (
        reason_code is null or (length(btrim(reason_code)) between 1 and 40 and reason_code !~ '[[:cntrl:]]')
    ),
    constraint ai_feedback_export_lease_shape check (
        (export_lease_owner is null and export_lease_expires_at is null)
        or (export_lease_owner is not null and export_lease_expires_at is not null)
    )
);
create index ai_feedback_export_idx on ai_feedback (next_export_at, updated_at, job_id)
    where exported_revision < source_revision;

create table ai_operations (
    operation_id uuid primary key,
    job_id uuid null,
    action varchar(20) not null,
    reason varchar(500) not null,
    status varchar(20) not null,
    expected_generation integer null,
    result_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null,
    completed_at timestamptz null,
    constraint ai_operation_action_valid check (action in ('RETRY', 'CANCEL', 'RECONCILE', 'RETENTION')),
    constraint ai_operation_status_valid check (status in ('ACCEPTED', 'SUCCEEDED', 'FAILED')),
    constraint ai_operation_result_object check (jsonb_typeof(result_json) = 'object')
);

create table ai_dead_letters (
    id uuid primary key,
    job_id uuid null,
    source varchar(32) not null,
    error_code varchar(80) not null,
    attempts integer not null,
    metadata_json jsonb not null,
    created_at timestamptz not null,
    constraint ai_dead_letter_source_valid check (source in ('INGRESS', 'DISPATCH', 'WORKER', 'INDEX')),
    constraint ai_dead_letter_metadata_object check (jsonb_typeof(metadata_json) = 'object')
);
create index ai_dead_letters_created_idx on ai_dead_letters (created_at desc, id desc);
