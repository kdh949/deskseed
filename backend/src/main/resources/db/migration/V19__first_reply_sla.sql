create table sla_policies (
    id uuid primary key,
    current_version integer not null,
    active_version integer null,
    aggregate_version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint sla_policy_current_version_positive check (current_version >= 1),
    constraint sla_policy_active_version_valid check (
        active_version is null or (active_version >= 1 and active_version <= current_version)
    )
);

create table sla_policy_versions (
    policy_id uuid not null references sla_policies(id),
    version integer not null,
    name varchar(100) not null,
    position integer not null,
    schedule_id uuid not null,
    schedule_version integer not null,
    condition_group_id uuid null references support_groups(id),
    condition_channel varchar(20) null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_by_display varchar(100) not null,
    created_at timestamptz not null,
    primary key (policy_id, version),
    foreign key (schedule_id, schedule_version)
        references business_schedule_versions(schedule_id, version),
    constraint sla_policy_version_positive check (version >= 1),
    constraint sla_policy_name_not_blank check (length(btrim(name)) between 1 and 100),
    constraint sla_policy_position_valid check (position between 1 and 10000),
    constraint sla_policy_channel_valid check (
        condition_channel is null or condition_channel in ('WEB', 'AGENT', 'EMAIL', 'CHAT', 'API')
    )
);

alter table sla_policies
    add constraint sla_policy_current_version_fk
        foreign key (id, current_version)
        references sla_policy_versions(policy_id, version)
        deferrable initially deferred,
    add constraint sla_policy_active_version_fk
        foreign key (id, active_version)
        references sla_policy_versions(policy_id, version)
        deferrable initially deferred;

create table sla_policy_priority_targets (
    policy_id uuid not null,
    policy_version integer not null,
    priority varchar(20) not null,
    target_minutes integer not null,
    primary key (policy_id, policy_version, priority),
    foreign key (policy_id, policy_version)
        references sla_policy_versions(policy_id, version),
    constraint sla_policy_target_priority_valid check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    constraint sla_policy_target_minutes_valid check (target_minutes between 1 and 525600)
);

create table sla_policy_pause_statuses (
    policy_id uuid not null,
    policy_version integer not null,
    status varchar(20) not null,
    primary key (policy_id, policy_version, status),
    foreign key (policy_id, policy_version)
        references sla_policy_versions(policy_id, version),
    constraint sla_policy_pause_status_valid check (
        status in ('NEW', 'OPEN', 'PENDING', 'ON_HOLD')
    )
);

create table sla_policy_activations (
    id uuid primary key,
    policy_id uuid not null,
    policy_version integer not null,
    actor_id uuid not null references staff_accounts(id),
    actor_display_snapshot varchar(100) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    activated_at timestamptz not null,
    foreign key (policy_id, policy_version)
        references sla_policy_versions(policy_id, version)
);

create table ticket_state_intervals (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    status varchar(20) not null,
    started_at timestamptz not null,
    ended_at timestamptz null,
    start_audit_id uuid not null references ticket_audits(id),
    end_audit_id uuid null references ticket_audits(id),
    constraint ticket_state_interval_status_valid check (
        status in ('NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED', 'CLOSED')
    ),
    constraint ticket_state_interval_range_valid check (ended_at is null or started_at <= ended_at),
    constraint ticket_state_interval_end_audit_valid check (
        (ended_at is null and end_audit_id is null) or (ended_at is not null and end_audit_id is not null)
    )
);

create unique index ticket_state_intervals_one_open_idx
    on ticket_state_intervals (ticket_id) where ended_at is null;
create index ticket_state_intervals_rebuild_idx
    on ticket_state_intervals (ticket_id, started_at, id);

create table sla_target_instances (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    metric varchar(30) not null,
    policy_id uuid not null,
    policy_version integer not null,
    schedule_id uuid not null,
    schedule_version integer not null,
    priority_snapshot varchar(20) not null,
    target_minutes integer not null,
    pause_statuses varchar(20)[] not null,
    state varchar(20) not null,
    started_at timestamptz not null,
    active_segment_started_at timestamptz null,
    due_at timestamptz null,
    remaining_business_minutes integer not null,
    achieved_at timestamptz null,
    breached_at timestamptz null,
    cancelled_at timestamptz null,
    calculation_version varchar(40) not null,
    version bigint not null default 0,
    updated_at timestamptz not null,
    unique (ticket_id, metric),
    foreign key (policy_id, policy_version)
        references sla_policy_versions(policy_id, version),
    foreign key (schedule_id, schedule_version)
        references business_schedule_versions(schedule_id, version),
    constraint sla_target_metric_first_reply check (metric = 'FIRST_REPLY'),
    constraint sla_target_priority_valid check (priority_snapshot in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    constraint sla_target_minutes_valid check (target_minutes between 1 and 525600),
    constraint sla_target_remaining_valid check (
        remaining_business_minutes between 0 and target_minutes
    ),
    constraint sla_target_state_valid check (
        state in ('ACTIVE', 'PAUSED', 'ACHIEVED', 'BREACHED', 'CANCELLED')
    ),
    constraint sla_target_state_shape_valid check (
        (state = 'ACTIVE' and due_at is not null and active_segment_started_at is not null
            and achieved_at is null and breached_at is null and cancelled_at is null) or
        (state = 'PAUSED' and due_at is null and active_segment_started_at is null
            and achieved_at is null and breached_at is null and cancelled_at is null) or
        (state = 'ACHIEVED' and active_segment_started_at is null
            and achieved_at is not null and breached_at is null and cancelled_at is null) or
        (state = 'BREACHED' and due_at is not null and active_segment_started_at is null
            and achieved_at is null and breached_at is not null and cancelled_at is null) or
        (state = 'CANCELLED' and active_segment_started_at is null
            and achieved_at is null and breached_at is null and cancelled_at is not null)
    )
);

create index sla_target_instances_breach_scan_idx
    on sla_target_instances (due_at, id) where state = 'ACTIVE';
create index sla_target_instances_ticket_projection_idx
    on sla_target_instances (ticket_id, state, due_at);
create index sla_target_instances_policy_reporting_idx
    on sla_target_instances (policy_id, policy_version, state, started_at);

create table sla_target_events (
    id uuid primary key,
    target_id uuid not null references sla_target_instances(id),
    event_type varchar(40) not null,
    previous_state varchar(20) null,
    next_state varchar(20) not null,
    actor_type varchar(30) not null,
    actor_id uuid null,
    source varchar(40) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    ticket_audit_id uuid null references ticket_audits(id),
    metadata_json jsonb not null default '{}'::jsonb,
    occurred_at timestamptz not null,
    constraint sla_target_event_actor_valid check (
        actor_type in ('CUSTOMER', 'STAFF', 'INTEGRATION_CLIENT', 'TRIGGER', 'AUTOMATION', 'SYSTEM')
    ),
    constraint sla_target_event_state_valid check (
        next_state in ('ACTIVE', 'PAUSED', 'ACHIEVED', 'BREACHED', 'CANCELLED') and
        (previous_state is null or previous_state in ('ACTIVE', 'PAUSED', 'ACHIEVED', 'BREACHED', 'CANCELLED'))
    )
);

create index sla_target_events_history_idx
    on sla_target_events (target_id, occurred_at, id);

create table analytics_first_reply_facts (
    ticket_id uuid primary key references tickets(id),
    target_id uuid null unique references sla_target_instances(id),
    outcome varchar(20) not null,
    priority_snapshot varchar(20) not null,
    policy_id uuid null,
    policy_version integer null,
    schedule_id uuid null,
    schedule_version integer null,
    target_minutes integer null,
    started_at timestamptz not null,
    due_at timestamptz null,
    achieved_at timestamptz null,
    breached_at timestamptz null,
    cancelled_at timestamptz null,
    calculation_version varchar(40) not null,
    projected_at timestamptz not null,
    constraint analytics_first_reply_outcome_valid check (
        outcome in ('NO_POLICY', 'ACTIVE', 'PAUSED', 'ACHIEVED', 'BREACHED', 'CANCELLED')
    ),
    constraint analytics_first_reply_priority_valid check (
        priority_snapshot in ('LOW', 'NORMAL', 'HIGH', 'URGENT')
    ),
    constraint analytics_first_reply_policy_shape_valid check (
        (outcome = 'NO_POLICY' and target_id is null and policy_id is null and policy_version is null
            and schedule_id is null and schedule_version is null and target_minutes is null) or
        (outcome <> 'NO_POLICY' and target_id is not null and policy_id is not null and policy_version is not null
            and schedule_id is not null and schedule_version is not null and target_minutes is not null)
    )
);

create index analytics_first_reply_facts_summary_idx
    on analytics_first_reply_facts (outcome, priority_snapshot, started_at);
create index analytics_first_reply_facts_policy_idx
    on analytics_first_reply_facts (policy_id, policy_version, outcome, started_at);

create table sla_breach_scan_state (
    id smallint primary key,
    lease_owner varchar(100) null,
    lease_until timestamptz null,
    last_started_at timestamptz null,
    last_completed_at timestamptz null,
    last_target_due_at timestamptz null,
    last_target_id uuid null,
    last_claimed_count integer not null default 0,
    last_breached_count integer not null default 0,
    constraint sla_breach_scan_singleton check (id = 1),
    constraint sla_breach_scan_counts_valid check (last_claimed_count >= 0 and last_breached_count >= 0)
);

insert into sla_breach_scan_state (id) values (1);

create or replace function reject_first_reply_sla_history_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'First Reply SLA version and event history is immutable';
end;
$$;

create trigger sla_policy_versions_immutable
before update or delete on sla_policy_versions
for each row execute function reject_first_reply_sla_history_mutation();
create trigger sla_policy_priority_targets_immutable
before update or delete on sla_policy_priority_targets
for each row execute function reject_first_reply_sla_history_mutation();
create trigger sla_policy_pause_statuses_immutable
before update or delete on sla_policy_pause_statuses
for each row execute function reject_first_reply_sla_history_mutation();
create trigger sla_policy_activations_immutable
before update or delete on sla_policy_activations
for each row execute function reject_first_reply_sla_history_mutation();
create trigger sla_target_events_immutable
before update or delete on sla_target_events
for each row execute function reject_first_reply_sla_history_mutation();

create or replace function reject_first_reply_sla_root_delete()
returns trigger
language plpgsql
as $$
begin
    raise exception 'First Reply SLA policies and targets cannot be deleted';
end;
$$;

create trigger sla_policies_no_delete
before delete on sla_policies
for each row execute function reject_first_reply_sla_root_delete();
create trigger sla_target_instances_no_delete
before delete on sla_target_instances
for each row execute function reject_first_reply_sla_root_delete();

create or replace function reject_first_reply_sla_target_snapshot_mutation()
returns trigger
language plpgsql
as $$
begin
    if new.ticket_id is distinct from old.ticket_id
       or new.metric is distinct from old.metric
       or new.policy_id is distinct from old.policy_id
       or new.policy_version is distinct from old.policy_version
       or new.schedule_id is distinct from old.schedule_id
       or new.schedule_version is distinct from old.schedule_version
       or new.priority_snapshot is distinct from old.priority_snapshot
       or new.target_minutes is distinct from old.target_minutes
       or new.pause_statuses is distinct from old.pause_statuses
       or new.started_at is distinct from old.started_at
       or new.calculation_version is distinct from old.calculation_version then
        raise exception 'First Reply SLA target snapshot is immutable';
    end if;
    return new;
end;
$$;

create trigger sla_target_instances_snapshot_immutable
before update on sla_target_instances
for each row execute function reject_first_reply_sla_target_snapshot_mutation();
