-- V75 initial time-based automation: versioned solved-age policy and system-only close action.
create table automation_definitions (
    id uuid primary key,
    normalized_name varchar(120) not null unique,
    name varchar(120) not null,
    position integer not null,
    current_version integer not null,
    active_version integer null,
    aggregate_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint automation_definitions_name_valid check (length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'),
    constraint automation_definitions_normalized_name_valid check (normalized_name = lower(btrim(name)) and length(normalized_name) between 1 and 120),
    constraint automation_definitions_position_valid check (position between 1 and 10000),
    constraint automation_definitions_versions_valid check (current_version >= 1 and (active_version is null or active_version between 1 and current_version)),
    constraint automation_definitions_aggregate_version_valid check (aggregate_version >= 1),
    constraint automation_definitions_position_key unique (position) deferrable initially immediate
);

create table automation_versions (
    automation_id uuid not null references automation_definitions(id),
    version integer not null,
    name varchar(120) not null,
    solved_age_minutes integer not null,
    action_type varchar(24) not null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_by_display varchar(100) not null,
    created_at timestamptz not null,
    primary key (automation_id, version),
    constraint automation_versions_version_valid check (version >= 1),
    constraint automation_versions_age_valid check (solved_age_minutes between 1 and 525600),
    constraint automation_versions_action_valid check (action_type = 'CLOSE_TICKET'),
    constraint automation_versions_name_valid check (length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]')
);

alter table automation_definitions add constraint automation_definitions_active_version_fk
    foreign key (id, active_version) references automation_versions(automation_id, version) deferrable initially deferred;

create table automation_activations (
    id uuid primary key,
    automation_id uuid not null,
    automation_version integer not null,
    activation_state varchar(16) not null,
    actor_staff_id uuid not null references staff_accounts(id),
    actor_display varchar(100) not null,
    source varchar(30) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    occurred_at timestamptz not null,
    foreign key (automation_id, automation_version) references automation_versions(automation_id, version),
    constraint automation_activations_state_valid check (activation_state in ('ACTIVE', 'INACTIVE')),
    constraint automation_activations_source_valid check (source = 'ADMIN_UI')
);

create table automation_candidates (
    id uuid primary key,
    automation_id uuid not null,
    automation_version integer not null,
    ticket_id uuid not null references tickets(id),
    ticket_number bigint not null,
    solved_at timestamptz not null,
    eligible_at timestamptz not null,
    status varchar(20) not null,
    attempt_count integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    last_error_code varchar(80) null,
    discovered_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz null,
    foreign key (automation_id, automation_version) references automation_versions(automation_id, version),
    constraint automation_candidates_status_valid check (status in ('PENDING', 'LEASED', 'SUCCEEDED', 'SKIPPED', 'RETRY_SCHEDULED', 'DEAD_LETTERED')),
    constraint automation_candidates_attempt_valid check (attempt_count between 0 and 20),
    constraint automation_candidates_lease_shape check (
        (status = 'LEASED' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'LEASED' and lease_owner is null and lease_expires_at is null)
    ),
    unique (automation_id, automation_version, ticket_id, solved_at)
);

create index automation_candidates_claim_idx on automation_candidates (available_at, discovered_at, id)
    where status in ('PENDING', 'RETRY_SCHEDULED');

create table automation_executions (
    id uuid primary key,
    candidate_id uuid not null unique references automation_candidates(id),
    automation_id uuid not null,
    automation_version integer not null,
    ticket_id uuid not null references tickets(id),
    solved_at timestamptz not null,
    outcome varchar(32) not null,
    ticket_audit_id uuid null references ticket_audits(id),
    error_code varchar(80) null,
    started_at timestamptz not null,
    completed_at timestamptz not null,
    foreign key (automation_id, automation_version) references automation_versions(automation_id, version),
    constraint automation_executions_outcome_valid check (outcome in ('CLOSED', 'SKIPPED_STATE_CHANGED', 'FAILED'))
);

create index tickets_solved_automation_candidate_idx on tickets (solved_at, id)
    where status = 'SOLVED' and solved_at is not null;

create or replace function reject_automation_history_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'automation history rows are immutable';
end;
$$;

create trigger automation_versions_immutable before update or delete on automation_versions
for each row execute function reject_automation_history_mutation();
create trigger automation_activations_immutable before update or delete on automation_activations
for each row execute function reject_automation_history_mutation();
create trigger automation_executions_immutable before update or delete on automation_executions
for each row execute function reject_automation_history_mutation();
