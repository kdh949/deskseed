create table domain_event_outbox (
    id uuid primary key,
    event_type varchar(160) not null,
    event_version integer not null,
    occurred_at timestamptz not null,
    subject varchar(140) not null,
    subject_sequence bigint not null,
    correlation_id varchar(100) not null,
    causation_id varchar(100),
    visibility varchar(20) not null,
    data_json jsonb not null,
    actor_type varchar(30) not null,
    actor_id uuid,
    source varchar(40) not null,
    request_id varchar(100) not null,
    command_id varchar(100) not null,
    status varchar(20) not null,
    attempt_count integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100),
    lease_expires_at timestamptz,
    delivered_at timestamptz,
    dead_lettered_at timestamptz,
    created_at timestamptz not null,
    constraint domain_event_outbox_type_valid check (event_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'),
    constraint domain_event_outbox_version_valid check (event_version > 0),
    constraint domain_event_outbox_sequence_valid check (subject_sequence >= 0),
    constraint domain_event_outbox_visibility_valid check (visibility in ('PUBLIC', 'INTERNAL')),
    constraint domain_event_outbox_data_object check (jsonb_typeof(data_json) = 'object'),
    constraint domain_event_outbox_status_valid check (status in ('PENDING', 'LEASED', 'DELIVERED', 'DEAD_LETTER')),
    constraint domain_event_outbox_attempt_count_valid check (attempt_count >= 0),
    constraint domain_event_outbox_lease_state check (
        (status = 'LEASED' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'LEASED' and lease_owner is null and lease_expires_at is null)
    ),
    constraint domain_event_outbox_terminal_timestamps check (
        (status = 'DELIVERED' and delivered_at is not null and dead_lettered_at is null)
        or (status = 'DEAD_LETTER' and dead_lettered_at is not null and delivered_at is null)
        or (status in ('PENDING', 'LEASED') and delivered_at is null and dead_lettered_at is null)
    ),
    constraint domain_event_outbox_subject_sequence_unique unique (subject, subject_sequence)
);

create index domain_event_outbox_claim_idx
    on domain_event_outbox (status, available_at, occurred_at, id)
    where status = 'PENDING';

create index domain_event_outbox_lease_recovery_idx
    on domain_event_outbox (lease_expires_at)
    where status = 'LEASED';
