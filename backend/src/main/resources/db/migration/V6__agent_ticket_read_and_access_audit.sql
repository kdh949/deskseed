create index tickets_status_cursor_idx
    on tickets (status, updated_at desc, ticket_number desc);

create index tickets_assignee_status_cursor_idx
    on tickets (assignee_id, status, updated_at desc, ticket_number desc);

create index tickets_group_status_cursor_idx
    on tickets (group_id, status, updated_at desc, ticket_number desc);

create index tickets_kind_assignee_cursor_idx
    on tickets (kind, assignee_id, updated_at desc, ticket_number desc);

create table access_audit_events (
    id uuid primary key,
    occurred_at timestamptz not null,
    actor_type varchar(30) not null,
    actor_id uuid not null,
    actor_display_snapshot varchar(100) not null,
    source varchar(40) not null,
    action varchar(60) not null,
    resource_type varchar(40) not null,
    resource_id uuid not null,
    ticket_number bigint null,
    interaction_id uuid null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    ip_address varchar(64) null,
    user_agent varchar(256) null,
    outcome varchar(20) not null,
    http_status integer not null,
    constraint access_audit_actor_type_valid check (actor_type in ('STAFF', 'INTEGRATION_CLIENT')),
    constraint access_audit_source_valid check (source in ('AGENT_UI', 'PLATFORM_API')),
    constraint access_audit_action_valid check (action in ('TICKET_VIEWED', 'API_RESOURCE_READ')),
    constraint access_audit_outcome_valid check (outcome in ('SUCCEEDED', 'DENIED', 'FAILED')),
    constraint access_audit_http_status_valid check (http_status between 100 and 599)
);

create unique index access_audit_semantic_ticket_view_unique
    on access_audit_events (actor_id, resource_id, interaction_id, action)
    where action = 'TICKET_VIEWED' and outcome = 'SUCCEEDED';

create index access_audit_occurred_idx
    on access_audit_events (occurred_at desc, id desc);

create index access_audit_actor_idx
    on access_audit_events (actor_id, occurred_at desc, id desc);

create index access_audit_ticket_idx
    on access_audit_events (resource_id, occurred_at desc, id desc);

create index access_audit_request_idx
    on access_audit_events (request_id);

create or replace function reject_access_audit_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Access audit history is append-only';
end;
$$;

create trigger access_audit_events_immutable
before update or delete on access_audit_events
for each row execute function reject_access_audit_mutation();
