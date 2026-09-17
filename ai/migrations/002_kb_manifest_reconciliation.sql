create table ai_kb_reconciliation_runs (
    run_id uuid primary key,
    workspace_key varchar(80) not null,
    snapshot_token uuid not null unique,
    snapshot_expires_at timestamptz not null,
    next_cursor uuid null,
    status varchar(20) not null,
    page_count integer not null default 0,
    item_count integer not null default 0,
    started_at timestamptz not null,
    completed_at timestamptz null,
    last_error_code varchar(80) null,
    constraint ai_kb_reconciliation_status_valid check (status in ('RUNNING', 'SUCCEEDED', 'FAILED')),
    constraint ai_kb_reconciliation_counts_nonnegative check (page_count >= 0 and item_count >= 0),
    constraint ai_kb_reconciliation_completion_shape check (
        (status = 'RUNNING' and completed_at is null)
        or (status <> 'RUNNING' and completed_at is not null)
    )
);

create unique index ai_kb_reconciliation_one_running_idx
    on ai_kb_reconciliation_runs (workspace_key) where status = 'RUNNING';
create index ai_kb_reconciliation_history_idx
    on ai_kb_reconciliation_runs (workspace_key, started_at desc, run_id desc);

create table ai_kb_reconciliation_seen (
    run_id uuid not null references ai_kb_reconciliation_runs(run_id) on delete cascade,
    article_id uuid not null,
    revision_id uuid not null,
    primary key (run_id, article_id)
);
