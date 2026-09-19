alter table ai_jobs
    add column result_candidate_id uuid null;

create index ai_jobs_result_candidate_idx
    on ai_jobs (result_candidate_id, completed_at)
    where result_candidate_id is not null;

create table ai_reply_sent_inbox (
    event_id uuid primary key,
    job_id uuid not null references ai_jobs(job_id),
    comment_id uuid not null,
    candidate_id uuid not null,
    event_fingerprint char(64) not null,
    received_at timestamptz not null,
    constraint ai_reply_sent_inbox_fingerprint_valid check (event_fingerprint ~ '^[0-9a-f]{64}$')
);

create index ai_reply_sent_inbox_job_idx on ai_reply_sent_inbox (job_id, received_at);

create table ai_reply_sent_usage (
    comment_id uuid not null,
    candidate_id uuid not null,
    job_id uuid not null references ai_jobs(job_id),
    requester_id uuid not null,
    attribution_kind varchar(24) not null,
    source_count integer not null,
    original_length integer null,
    final_length integer null,
    edit_distance integer null,
    inserted_length integer null,
    deleted_length integer null,
    edit_ratio numeric(9, 8) null,
    first_event_id uuid not null unique references ai_reply_sent_inbox(event_id),
    sent_at timestamptz not null,
    received_at timestamptz not null,
    primary key (comment_id, candidate_id),
    constraint ai_reply_sent_usage_kind_valid check (attribution_kind in ('SINGLE_SOURCE', 'MULTI_SOURCE')),
    constraint ai_reply_sent_usage_source_count_valid check (source_count between 1 and 4),
    constraint ai_reply_sent_usage_edit_shape check (
        (
            attribution_kind = 'SINGLE_SOURCE' and source_count = 1
            and original_length is not null and final_length is not null and edit_distance is not null
            and inserted_length is not null and deleted_length is not null and edit_ratio is not null
            and original_length between 1 and 6000 and final_length between 0 and 20000
            and edit_distance >= 0 and inserted_length >= 0 and deleted_length >= 0
            and edit_ratio between 0 and 1
        ) or (
            attribution_kind = 'MULTI_SOURCE' and source_count between 2 and 4
            and original_length is null and final_length is null and edit_distance is null
            and inserted_length is null and deleted_length is null and edit_ratio is null
        )
    )
);

create index ai_reply_sent_usage_candidate_idx
    on ai_reply_sent_usage (candidate_id, sent_at, comment_id);
