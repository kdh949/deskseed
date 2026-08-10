create table staff_accounts (
    id uuid primary key,
    email_normalized varchar(254) not null unique,
    email_display varchar(254) not null,
    display_name varchar(100) not null,
    role varchar(20) not null,
    status varchar(20) not null,
    password_hash varchar(100) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    last_login_at timestamptz null,
    version bigint not null default 0,
    constraint staff_email_normalized_not_blank check (length(btrim(email_normalized)) > 0),
    constraint staff_display_name_not_blank check (length(btrim(display_name)) > 0),
    constraint staff_role_valid check (role in ('ADMIN', 'AGENT')),
    constraint staff_status_valid check (status in ('ACTIVE', 'DISABLED'))
);

create index staff_accounts_status_role_idx on staff_accounts (status, role, display_name, id);

create table support_groups (
    id uuid primary key,
    name varchar(100) not null unique,
    status varchar(20) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint support_group_name_not_blank check (length(btrim(name)) > 0),
    constraint support_group_status_valid check (status in ('ACTIVE', 'DISABLED'))
);

create index support_groups_status_name_idx on support_groups (status, name, id);

create table group_memberships (
    id uuid primary key,
    group_id uuid not null references support_groups(id),
    staff_id uuid not null references staff_accounts(id),
    status varchar(20) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint group_membership_pair_unique unique (group_id, staff_id),
    constraint group_membership_status_valid check (status in ('ACTIVE', 'INACTIVE'))
);

create index group_memberships_staff_active_idx on group_memberships (staff_id, status, group_id);
create index group_memberships_group_active_idx on group_memberships (group_id, status, staff_id);

alter table tickets
    add constraint ticket_group_fk foreign key (group_id) references support_groups(id),
    add constraint ticket_assignee_fk foreign key (assignee_id) references staff_accounts(id),
    add constraint ticket_assignee_requires_group check (assignee_id is null or group_id is not null);

create table admin_security_audit_events (
    id uuid primary key,
    event_type varchar(80) not null,
    actor_type varchar(30) not null,
    actor_id uuid null,
    actor_display_snapshot varchar(100) null,
    source varchar(40) not null,
    target_type varchar(60) not null,
    target_id uuid null,
    outcome varchar(20) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    metadata_json text not null default '{}',
    occurred_at timestamptz not null,
    constraint admin_security_actor_type_valid check (actor_type in ('STAFF', 'SYSTEM')),
    constraint admin_security_source_valid check (source in ('AGENT_UI', 'ADMIN_UI', 'SYSTEM_JOB')),
    constraint admin_security_outcome_valid check (outcome in ('SUCCEEDED', 'DENIED', 'FAILED')),
    constraint admin_security_metadata_json_valid check (metadata_json is json)
);

create index admin_security_audit_occurred_idx
    on admin_security_audit_events (occurred_at desc, id desc);
create index admin_security_audit_actor_idx
    on admin_security_audit_events (actor_id, occurred_at desc, id desc);
create index admin_security_audit_target_idx
    on admin_security_audit_events (target_type, target_id, occurred_at desc, id desc);
create index admin_security_audit_event_idx
    on admin_security_audit_events (event_type, occurred_at desc, id desc);
create index admin_security_audit_request_idx
    on admin_security_audit_events (request_id);

create or replace function reject_admin_security_audit_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Admin security audit history is append-only';
end;
$$;

create trigger admin_security_audit_events_immutable
before update or delete on admin_security_audit_events
for each row execute function reject_admin_security_audit_mutation();

create table staff_login_throttles (
    email_fingerprint char(64) not null,
    network_fingerprint char(64) not null,
    failure_count integer not null,
    window_started_at timestamptz not null,
    locked_until timestamptz null,
    updated_at timestamptz not null,
    primary key (email_fingerprint, network_fingerprint),
    constraint staff_login_failure_count_positive check (failure_count >= 0)
);

create index staff_login_throttles_cleanup_idx on staff_login_throttles (updated_at);
