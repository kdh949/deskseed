create table ai_embedding_batch_jobs (
    batch_job_id uuid primary key,
    event_id uuid not null unique references ai_kb_index_jobs(event_id),
    workspace_key varchar(80) not null,
    article_id uuid not null,
    revision_id uuid not null,
    source_version bigint not null,
    public_revision char(64) not null,
    artifact_generation bigint not null,
    reconciliation_run_id uuid not null references ai_kb_reconciliation_runs(run_id),
    purpose varchar(16) not null,
    mode varchar(16) not null,
    contract_version varchar(40) not null,
    status varchar(24) not null,
    terminal_status varchar(16) null,
    model_alias varchar(100) not null,
    model_snapshot varchar(160) not null,
    embedding_dimension integer not null,
    normalization_version varchar(80) not null,
    pricing_version varchar(40) not null,
    completion_window varchar(8) not null,
    manifest_digest char(64) not null,
    request_count integer not null,
    input_count integer not null,
    input_token_count bigint not null,
    input_bytes bigint not null,
    reservation_id uuid null unique references ai_cost_ledger(reservation_id),
    provider_call_id uuid null unique references ai_provider_calls(call_id),
    provider_input_file_id varchar(200) null,
    provider_batch_id varchar(200) null,
    provider_output_file_id varchar(200) null,
    provider_error_file_id varchar(200) null,
    provider_request_total integer null,
    provider_request_completed integer null,
    provider_request_failed integer null,
    cancel_requested boolean not null default false,
    cancel_sent_at timestamptz null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    available_at timestamptz not null,
    attempts integer not null default 0,
    last_error_code varchar(80) null,
    created_at timestamptz not null,
    submitted_at timestamptz null,
    provider_terminal_at timestamptz null,
    finalized_at timestamptz null,
    completed_at timestamptz null,
    updated_at timestamptz not null,
    constraint ai_embedding_batch_source_version_positive check (source_version > 0),
    constraint ai_embedding_batch_artifact_generation_positive check (artifact_generation > 0),
    constraint ai_embedding_batch_purpose_valid check (purpose in ('INDEX', 'EVAL')),
    constraint ai_embedding_batch_mode_valid check (mode in ('test', 'intent')),
    constraint ai_embedding_batch_status_valid check (status in (
        'PREPARING', 'UPLOADING', 'SUBMITTING', 'IN_PROGRESS', 'FINALIZING',
        'CANCELLING', 'CLEANUP_PENDING', 'COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED'
    )),
    constraint ai_embedding_batch_terminal_valid check (
        terminal_status is null or terminal_status in ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED')
    ),
    constraint ai_embedding_batch_contract_valid check (contract_version = 'embedding-batch-v1'),
    constraint ai_embedding_batch_dimension_valid check (embedding_dimension = 1536),
    constraint ai_embedding_batch_window_valid check (completion_window = '24h'),
    constraint ai_embedding_batch_manifest_shape check (manifest_digest ~ '^[0-9a-f]{64}$'),
    constraint ai_embedding_batch_public_revision_shape check (public_revision ~ '^[0-9a-f]{64}$'),
    constraint ai_embedding_batch_counts_valid check (
        request_count between 1 and 512
        and input_count between 1 and 2048
        and input_token_count > 0
        and input_bytes between 1 and 20971520
        and attempts >= 0
    ),
    constraint ai_embedding_batch_provider_counts_valid check (
        (provider_request_total is null and provider_request_completed is null and provider_request_failed is null)
        or (
            provider_request_total >= 0
            and provider_request_completed >= 0
            and provider_request_failed >= 0
            and provider_request_completed + provider_request_failed <= provider_request_total
        )
    ),
    constraint ai_embedding_batch_budget_shape check (
        (reservation_id is null and provider_call_id is null)
        or (reservation_id is not null and provider_call_id is not null)
    ),
    constraint ai_embedding_batch_lease_shape check (
        (lease_owner is null and lease_expires_at is null)
        or (lease_owner is not null and lease_expires_at is not null)
    )
);

create index ai_embedding_batch_due_idx
    on ai_embedding_batch_jobs (available_at, created_at, batch_job_id)
    where status not in ('COMPLETED', 'CANCELLED', 'FAILED', 'EXPIRED');
create index ai_embedding_batch_cleanup_idx
    on ai_embedding_batch_jobs (updated_at, batch_job_id)
    where status = 'CLEANUP_PENDING';

create table ai_embedding_batch_items (
    batch_job_id uuid not null references ai_embedding_batch_jobs(batch_job_id) on delete cascade,
    ordinal integer not null,
    custom_id varchar(80) not null,
    artifact_key char(64) not null,
    model_snapshot varchar(160) not null,
    embedding_dimension integer not null,
    normalization_version varchar(80) not null,
    embedding_input_sha256 char(64) not null,
    result_status varchar(16) not null default 'PENDING',
    actual_model varchar(160) null,
    input_tokens bigint null,
    error_code varchar(80) null,
    finalized_at timestamptz null,
    primary key (batch_job_id, ordinal),
    unique (batch_job_id, custom_id),
    unique (batch_job_id, artifact_key),
    constraint ai_embedding_batch_item_ordinal_valid check (ordinal between 0 and 2047),
    constraint ai_embedding_batch_item_custom_id_valid check (
        custom_id ~ '^b-[0-9]{4}-[0-9a-f]{16}$'
    ),
    constraint ai_embedding_batch_item_artifact_shape check (
        artifact_key ~ '^[0-9a-f]{64}$' and embedding_input_sha256 ~ '^[0-9a-f]{64}$'
    ),
    constraint ai_embedding_batch_item_dimension_valid check (embedding_dimension = 1536),
    constraint ai_embedding_batch_item_result_valid check (
        result_status in ('PENDING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')
    ),
    constraint ai_embedding_batch_item_usage_valid check (input_tokens is null or input_tokens >= 0)
);

create table ai_embedding_batch_files (
    batch_job_id uuid not null references ai_embedding_batch_jobs(batch_job_id) on delete cascade,
    file_role varchar(16) not null,
    provider_file_id varchar(200) not null,
    cleanup_status varchar(20) not null,
    delete_attempts integer not null default 0,
    last_error_code varchar(80) null,
    delete_acknowledged_at timestamptz null,
    updated_at timestamptz not null,
    primary key (batch_job_id, file_role),
    unique (provider_file_id),
    constraint ai_embedding_batch_file_role_valid check (file_role in ('INPUT', 'OUTPUT', 'ERROR')),
    constraint ai_embedding_batch_file_cleanup_valid check (
        cleanup_status in ('PENDING_DELETE', 'DELETING', 'DELETED')
    ),
    constraint ai_embedding_batch_file_attempts_valid check (delete_attempts >= 0),
    constraint ai_embedding_batch_file_ack_shape check (
        (cleanup_status = 'DELETED' and delete_acknowledged_at is not null)
        or (cleanup_status <> 'DELETED' and delete_acknowledged_at is null)
    )
);
