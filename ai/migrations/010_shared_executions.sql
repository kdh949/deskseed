create table ai_shared_executions (
    execution_id uuid primary key,
    execution_key char(64) not null,
    key_version varchar(40) not null,
    workspace_key varchar(80) not null,
    requester_id uuid not null,
    ticket_id uuid not null,
    feature varchar(32) not null,
    representative_job_id uuid not null references ai_jobs(job_id),
    status varchar(24) not null,
    phase varchar(24) not null,
    execution_generation integer not null default 1,
    lease_epoch bigint not null default 0,
    provider_dispatched boolean not null default false,
    terminal_reason varchar(80) null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz null,
    constraint ai_shared_execution_key_shape check (execution_key ~ '^[0-9a-f]{64}$'),
    constraint ai_shared_execution_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft')
    ),
    constraint ai_shared_execution_status_valid check (
        status in ('RUNNING', 'SUCCEEDED', 'NEEDS_REVIEW', 'FAILED', 'UNKNOWN', 'CANCELLED')
    ),
    constraint ai_shared_execution_phase_valid check (
        phase in ('AUTHORIZE', 'READY', 'QUERY_EMBEDDING', 'GENERATION', 'COMPLETE')
    ),
    constraint ai_shared_execution_generation_positive check (
        execution_generation > 0 and lease_epoch >= 0
    ),
    constraint ai_shared_execution_completion_shape check (
        (status = 'RUNNING' and completed_at is null and terminal_reason is null and phase <> 'COMPLETE')
        or (status <> 'RUNNING' and completed_at is not null and phase = 'COMPLETE')
    )
);

create unique index ai_shared_execution_one_running_key_idx
    on ai_shared_executions (execution_key) where status = 'RUNNING';
create index ai_shared_execution_retention_idx
    on ai_shared_executions (completed_at, execution_id) where status <> 'RUNNING';

create table ai_execution_consumers (
    execution_id uuid not null references ai_shared_executions(execution_id) on delete cascade,
    job_id uuid not null unique references ai_jobs(job_id),
    state varchar(24) not null,
    joined_at timestamptz not null,
    woken_at timestamptz null,
    completed_at timestamptz null,
    primary key (execution_id, job_id),
    constraint ai_execution_consumer_state_valid check (
        state in ('REPRESENTATIVE', 'WAITING', 'COMPLETED', 'CANCELLED', 'FAILED')
    ),
    constraint ai_execution_consumer_completion_shape check (
        (state in ('REPRESENTATIVE', 'WAITING') and completed_at is null)
        or (state in ('COMPLETED', 'CANCELLED', 'FAILED') and completed_at is not null)
    )
);

create index ai_execution_consumers_waiting_idx
    on ai_execution_consumers (execution_id, joined_at, job_id) where state = 'WAITING';

alter table ai_jobs
    add column shared_execution_id uuid null references ai_shared_executions(execution_id);

create index ai_jobs_shared_execution_idx
    on ai_jobs (shared_execution_id, status, deadline_at) where shared_execution_id is not null;

alter table ai_cost_ledger
    add column execution_id uuid null references ai_shared_executions(execution_id),
    add constraint ai_cost_execution_actor_shape check (
        execution_id is null or (job_id is not null and budget_bucket = 'ACTOR')
    );

create index ai_cost_execution_idx
    on ai_cost_ledger (execution_id, created_at, reservation_id) where execution_id is not null;

alter table ai_provider_calls
    add column execution_id uuid null references ai_shared_executions(execution_id);

create index ai_provider_calls_execution_idx
    on ai_provider_calls (execution_id, created_at, call_id) where execution_id is not null;
