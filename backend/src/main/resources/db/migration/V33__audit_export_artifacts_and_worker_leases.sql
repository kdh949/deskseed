alter table audit_export_jobs
    drop constraint audit_export_status_valid,
    add column snapshot_at timestamptz not null default clock_timestamp(),
    add column lease_owner varchar(100) null,
    add column lease_expires_at timestamptz null,
    add column attempt_count integer not null default 0,
    add column started_at timestamptz null,
    add column completed_at timestamptz null,
    add column failed_at timestamptz null,
    add column failure_code varchar(80) null;

alter table audit_export_jobs
    add constraint audit_export_status_valid check (status in ('REQUESTED', 'RUNNING', 'READY', 'FAILED', 'EXPIRED')),
    add constraint audit_export_attempt_count_valid check (attempt_count >= 0),
    add constraint audit_export_lease_shape_valid check (
        (status = 'RUNNING' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'RUNNING' and lease_owner is null and lease_expires_at is null)
    ),
    add constraint audit_export_failure_code_bounded check (
        failure_code is null or (length(failure_code) between 1 and 80 and failure_code !~ '[[:cntrl:]]')
    );

create index audit_export_jobs_claim_idx
    on audit_export_jobs (status, lease_expires_at, created_at, id)
    where status in ('REQUESTED', 'RUNNING');

alter table audit_export_artifacts
    drop constraint audit_export_artifact_state_valid,
    drop constraint audit_export_artifact_generation_unavailable,
    add column object_key varchar(160) null,
    add column content_type varchar(80) null,
    add column row_count bigint null,
    add column size_bytes bigint null,
    add column checksum_sha256 varchar(64) null,
    add column expires_at timestamptz null,
    add column failure_code varchar(80) null,
    add column deleted_at timestamptz null;

update audit_export_artifacts
set state = 'PENDING'
where state = 'NOT_CREATED';

alter table audit_export_artifacts
    add constraint audit_export_artifact_state_valid check (state in ('PENDING', 'READY', 'FAILED', 'EXPIRED', 'DELETED')),
    add constraint audit_export_artifact_object_key_shape check (
        object_key is null or object_key ~ '^audit-exports/[0-9a-f-]{36}/attempt-[1-9][0-9]*\.(csv|jsonl)$'
    ),
    add constraint audit_export_artifact_content_type_valid check (
        content_type is null or content_type in ('text/csv', 'application/x-ndjson')
    ),
    add constraint audit_export_artifact_row_count_valid check (row_count is null or row_count >= 0),
    add constraint audit_export_artifact_size_valid check (size_bytes is null or size_bytes >= 0),
    add constraint audit_export_artifact_checksum_shape check (
        checksum_sha256 is null or checksum_sha256 ~ '^[0-9a-f]{64}$'
    ),
    add constraint audit_export_artifact_failure_code_bounded check (
        failure_code is null or (length(failure_code) between 1 and 80 and failure_code !~ '[[:cntrl:]]')
    ),
    add constraint audit_export_artifact_ready_shape check (
        state <> 'READY' or (
            generation_available and object_key is not null and content_type is not null and row_count is not null
            and size_bytes is not null and checksum_sha256 is not null and expires_at is not null
        )
    ),
    add constraint audit_export_artifact_pending_shape check (
        state <> 'PENDING' or (not generation_available and object_key is null and checksum_sha256 is null)
    );

create index audit_export_artifacts_expiry_idx
    on audit_export_artifacts (expires_at, job_id)
    where state = 'READY';
