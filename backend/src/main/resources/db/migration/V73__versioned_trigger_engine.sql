-- Trigger definitions keep an editable aggregate root and immutable versions.
-- Ticket mutations only append a durable evaluation job; workers perform no network I/O.
create table trigger_definitions (
    id uuid primary key,
    normalized_name varchar(120) not null unique,
    name varchar(120) not null,
    position integer not null,
    current_version integer not null,
    active_version integer null,
    aggregate_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint trigger_definitions_name_valid check (length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'),
    constraint trigger_definitions_normalized_name_valid check (normalized_name = lower(btrim(name)) and length(normalized_name) between 1 and 120),
    constraint trigger_definitions_position_valid check (position between 1 and 10000),
    constraint trigger_definitions_versions_valid check (current_version >= 1 and (active_version is null or active_version between 1 and current_version)),
    constraint trigger_definitions_aggregate_version_valid check (aggregate_version >= 1),
    constraint trigger_definitions_position_key unique (position) deferrable initially immediate
);

create table trigger_versions (
    trigger_id uuid not null references trigger_definitions(id),
    version integer not null,
    name varchar(120) not null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_by_display varchar(100) not null,
    created_at timestamptz not null,
    primary key (trigger_id, version),
    constraint trigger_versions_version_valid check (version >= 1),
    constraint trigger_versions_name_valid check (length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'),
    constraint trigger_versions_actor_display_valid check (length(btrim(created_by_display)) between 1 and 100 and created_by_display !~ '[[:cntrl:]]')
);

alter table trigger_definitions add constraint trigger_definitions_active_version_fk
    foreign key (id, active_version) references trigger_versions(trigger_id, version) deferrable initially deferred;

create table trigger_conditions (
    trigger_id uuid not null,
    trigger_version integer not null,
    ordinal integer not null,
    condition_group varchar(8) not null,
    field_name varchar(32) not null,
    operator varchar(24) not null,
    value_text varchar(120) null,
    primary key (trigger_id, trigger_version, ordinal),
    foreign key (trigger_id, trigger_version) references trigger_versions(trigger_id, version),
    constraint trigger_conditions_ordinal_valid check (ordinal between 0 and 49),
    constraint trigger_conditions_group_valid check (condition_group in ('ALL', 'ANY')),
    constraint trigger_conditions_field_valid check (field_name in ('EVENT', 'PRIORITY', 'GROUP')),
    constraint trigger_conditions_operator_valid check (operator in ('IS', 'IS_NOT', 'PRESENT', 'NOT_PRESENT')),
    constraint trigger_conditions_value_valid check (
        (operator in ('PRESENT', 'NOT_PRESENT') and value_text is null)
        or (operator in ('IS', 'IS_NOT') and length(btrim(value_text)) between 1 and 120)
    )
);

create table trigger_actions (
    trigger_id uuid not null,
    trigger_version integer not null,
    ordinal integer not null,
    action_type varchar(32) not null,
    configuration_json jsonb not null,
    primary key (trigger_id, trigger_version, ordinal),
    foreign key (trigger_id, trigger_version) references trigger_versions(trigger_id, version),
    constraint trigger_actions_ordinal_valid check (ordinal between 0 and 49),
    constraint trigger_actions_type_valid check (action_type in ('SET_GROUP', 'ENQUEUE_WEBHOOK')),
    constraint trigger_actions_configuration_object check (jsonb_typeof(configuration_json) = 'object')
);

create table trigger_activations (
    id uuid primary key,
    trigger_id uuid not null,
    trigger_version integer not null,
    activation_state varchar(16) not null,
    actor_staff_id uuid not null references staff_accounts(id),
    actor_display varchar(100) not null,
    source varchar(30) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    occurred_at timestamptz not null,
    foreign key (trigger_id, trigger_version) references trigger_versions(trigger_id, version),
    constraint trigger_activations_state_valid check (activation_state in ('ACTIVE', 'INACTIVE')),
    constraint trigger_activations_source_valid check (source = 'ADMIN_UI'),
    constraint trigger_activations_context_valid check (
        length(request_id) between 1 and 100 and request_id !~ '[[:cntrl:]]'
        and length(correlation_id) between 1 and 100 and correlation_id !~ '[[:cntrl:]]'
    )
);

create index trigger_activations_history_idx on trigger_activations (trigger_id, occurred_at desc, id);

create table trigger_evaluation_jobs (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    ticket_number bigint not null,
    root_ticket_audit_id uuid not null references ticket_audits(id),
    root_correlation_id varchar(100) not null,
    event_type varchar(32) not null,
    trigger_versions_json jsonb not null,
    status varchar(20) not null,
    attempt_count integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    last_error_code varchar(80) null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz null,
    constraint trigger_jobs_event_valid check (event_type in ('TICKET_CREATED', 'TICKET_UPDATED')),
    constraint trigger_jobs_versions_array check (jsonb_typeof(trigger_versions_json) = 'array'),
    constraint trigger_jobs_status_valid check (status in ('PENDING', 'LEASED', 'SUCCEEDED', 'RETRY_SCHEDULED', 'DEAD_LETTERED')),
    constraint trigger_jobs_attempt_valid check (attempt_count between 0 and 20),
    constraint trigger_jobs_context_valid check (length(root_correlation_id) between 1 and 100 and root_correlation_id !~ '[[:cntrl:]]'),
    constraint trigger_jobs_lease_shape check (
        (status = 'LEASED' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'LEASED' and lease_owner is null and lease_expires_at is null)
    ),
    unique (root_ticket_audit_id, event_type)
);

create index trigger_evaluation_jobs_claim_idx on trigger_evaluation_jobs (available_at, created_at, id)
    where status in ('PENDING', 'RETRY_SCHEDULED');

create table trigger_executions (
    id uuid primary key,
    job_id uuid not null references trigger_evaluation_jobs(id),
    trigger_id uuid not null,
    trigger_version integer not null,
    position integer not null,
    outcome varchar(24) not null,
    state_fingerprint varchar(64) not null,
    ticket_audit_id uuid null references ticket_audits(id),
    error_code varchar(80) null,
    started_at timestamptz not null,
    completed_at timestamptz not null,
    foreign key (trigger_id, trigger_version) references trigger_versions(trigger_id, version),
    constraint trigger_executions_outcome_valid check (outcome in ('MATCHED', 'NOT_MATCHED', 'NO_OP', 'FAILED', 'LOOP_BLOCKED')),
    constraint trigger_executions_fingerprint_valid check (state_fingerprint ~ '^[a-f0-9]{64}$'),
    unique (job_id, trigger_id, trigger_version)
);

create index trigger_executions_history_idx on trigger_executions (trigger_id, trigger_version, completed_at desc, id);

create or replace function reject_trigger_history_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'trigger history rows are immutable';
end;
$$;

create trigger trigger_versions_immutable before update or delete on trigger_versions
for each row execute function reject_trigger_history_mutation();
create trigger trigger_conditions_immutable before update or delete on trigger_conditions
for each row execute function reject_trigger_history_mutation();
create trigger trigger_actions_immutable before update or delete on trigger_actions
for each row execute function reject_trigger_history_mutation();
create trigger trigger_activations_immutable before update or delete on trigger_activations
for each row execute function reject_trigger_history_mutation();
create trigger trigger_executions_immutable before update or delete on trigger_executions
for each row execute function reject_trigger_history_mutation();
