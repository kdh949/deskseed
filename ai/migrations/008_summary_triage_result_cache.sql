alter table ai_jobs
    add column result_origin_job_id uuid null,
    add column reuse_kind varchar(24) null,
    add constraint ai_job_result_reuse_shape check (
        (result_origin_job_id is null and reuse_kind is null)
        or (
            result_origin_job_id is not null
            and result_origin_job_id <> job_id
            and reuse_kind = 'EXACT_CACHE_HIT'
        )
    );

create table ai_result_cache (
    cache_key char(64) primary key,
    key_version varchar(40) not null,
    workspace_key varchar(80) not null,
    requester_id uuid not null,
    ticket_id uuid not null,
    feature varchar(32) not null,
    origin_job_id uuid not null references ai_jobs(job_id) on delete cascade,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    invalidated_at timestamptz null,
    invalidation_reason varchar(40) null,
    constraint ai_result_cache_key_shape check (cache_key ~ '^[0-9a-f]{64}$'),
    constraint ai_result_cache_key_version check (key_version = 'result-cache-summary-triage-v1'),
    constraint ai_result_cache_feature check (feature in ('ticket.summary', 'ticket.triage')),
    constraint ai_result_cache_expiry check (expires_at > created_at),
    constraint ai_result_cache_invalidation_shape check (
        (invalidated_at is null and invalidation_reason is null)
        or (invalidated_at is not null and invalidation_reason is not null)
    )
);

create index ai_result_cache_origin_idx on ai_result_cache (origin_job_id);
create index ai_result_cache_expiry_idx on ai_result_cache (expires_at, cache_key);
