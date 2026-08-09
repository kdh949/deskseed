create sequence ticket_number_seq start with 1000 increment by 1;

create table customers (
    id uuid primary key,
    name varchar(100) not null,
    email_normalized varchar(320) not null unique,
    email_display varchar(320) not null,
    verified_at timestamptz null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table system_settings (
    id integer primary key,
    customer_access_mode varchar(40) not null,
    updated_at timestamptz not null,
    constraint system_settings_singleton check (id = 1),
    constraint customer_access_mode_valid check (
        customer_access_mode in (
            'ANONYMOUS_ALLOWED',
            'REGISTRATION_OPTIONAL',
            'REGISTRATION_REQUIRED'
        )
    )
);

insert into system_settings (id, customer_access_mode, updated_at)
values (1, 'ANONYMOUS_ALLOWED', now());

create table tickets (
    id uuid primary key,
    ticket_number bigint not null unique,
    requester_id uuid not null references customers(id),
    kind varchar(40) not null,
    subject varchar(200) not null,
    status varchar(30) not null,
    priority varchar(20) not null,
    group_id uuid null,
    assignee_id uuid null,
    channel varchar(30) not null,
    version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    solved_at timestamptz null,
    constraint ticket_kind_valid check (kind in ('CUSTOMER_REQUEST', 'INTERNAL_CHILD', 'AGENT_CREATED')),
    constraint ticket_status_valid check (status in ('NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED', 'CLOSED')),
    constraint ticket_priority_valid check (priority in ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    constraint ticket_channel_valid check (channel in ('WEB', 'AGENT', 'EMAIL', 'CHAT', 'API')),
    constraint ticket_subject_not_blank check (length(btrim(subject)) > 0)
);

create index tickets_queue_idx on tickets (status, priority, updated_at desc, ticket_number desc);
create index tickets_requester_idx on tickets (requester_id, created_at desc);

create table ticket_comments (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    author_type varchar(30) not null,
    author_id uuid null,
    visibility varchar(20) not null,
    body text not null,
    created_at timestamptz not null,
    constraint comment_author_type_valid check (author_type in ('CUSTOMER', 'AGENT', 'SYSTEM', 'AUTOMATION')),
    constraint comment_visibility_valid check (visibility in ('PUBLIC', 'INTERNAL')),
    constraint comment_body_not_blank check (length(btrim(body)) > 0)
);

create index ticket_comments_timeline_idx
    on ticket_comments (ticket_id, visibility, created_at, id);

create table ticket_audits (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    ticket_version bigint not null,
    actor_type varchar(30) not null,
    actor_id uuid null,
    source varchar(40) not null,
    created_at timestamptz not null
);

create index ticket_audits_timeline_idx
    on ticket_audits (ticket_id, created_at, id);

create table ticket_audit_events (
    id uuid primary key,
    audit_id uuid not null references ticket_audits(id),
    event_order integer not null,
    event_type varchar(60) not null,
    field_name varchar(60) null,
    old_value_json text null,
    new_value_json text null,
    metadata_json text not null default '{}',
    constraint audit_event_order_unique unique (audit_id, event_order),
    constraint old_value_json_valid check (old_value_json is null or old_value_json is json),
    constraint new_value_json_valid check (new_value_json is null or new_value_json is json),
    constraint metadata_json_valid check (metadata_json is json)
);

create table request_access_tokens (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    token_hash varchar(64) not null unique,
    created_at timestamptz not null,
    expires_at timestamptz null,
    revoked_at timestamptz null
);

create index request_access_tokens_ticket_idx
    on request_access_tokens (ticket_id, created_at desc);

create or replace function reject_audit_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Ticket audit history is append-only';
end;
$$;

create trigger ticket_audits_immutable
before update or delete on ticket_audits
for each row execute function reject_audit_mutation();

create trigger ticket_audit_events_immutable
before update or delete on ticket_audit_events
for each row execute function reject_audit_mutation();
