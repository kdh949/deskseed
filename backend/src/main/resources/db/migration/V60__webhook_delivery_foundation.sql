create table webhook_endpoints (
    id uuid primary key,
    name varchar(100) not null,
    url varchar(2048) not null,
    enabled boolean not null,
    target_class varchar(30) not null,
    allowed_hostnames_json jsonb not null,
    allowed_ports_json jsonb not null,
    allowed_cidrs_json jsonb not null,
    health_state varchar(20) not null,
    cooldown_until timestamptz null,
    consecutive_failures integer not null default 0,
    last_succeeded_at timestamptz null,
    last_failed_at timestamptz null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deactivated_at timestamptz null,
    version bigint not null default 0,
    constraint webhook_endpoint_name_not_blank check (length(btrim(name)) > 0),
    constraint webhook_endpoint_https check (url ~ '^https://'),
    constraint webhook_endpoint_target_class check (target_class in ('PUBLIC', 'PRIVATE_APPROVED')),
    constraint webhook_endpoint_allowed_json check (
        jsonb_typeof(allowed_hostnames_json) = 'array'
        and jsonb_typeof(allowed_ports_json) = 'array'
        and jsonb_typeof(allowed_cidrs_json) = 'array'
    ),
    constraint webhook_endpoint_health_state check (health_state in ('CLOSED', 'OPEN', 'HALF_OPEN')),
    constraint webhook_endpoint_failure_count check (consecutive_failures >= 0)
);

create unique index webhook_endpoints_name_ci_active_unique
    on webhook_endpoints (lower(btrim(name)))
    where deactivated_at is null;
create index webhook_endpoints_enabled_idx
    on webhook_endpoints (enabled, updated_at desc, id)
    where deactivated_at is null;

create table webhook_endpoint_secrets (
    id uuid primary key,
    endpoint_id uuid not null references webhook_endpoints(id),
    sequence integer not null,
    ciphertext bytea not null,
    nonce bytea not null,
    key_version varchar(64) not null,
    status varchar(20) not null,
    overlap_expires_at timestamptz null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_at timestamptz not null,
    revoked_at timestamptz null,
    constraint webhook_endpoint_secret_sequence_unique unique (endpoint_id, sequence),
    constraint webhook_endpoint_secret_status check (status in ('ACTIVE', 'RETIRING', 'REVOKED')),
    constraint webhook_endpoint_secret_ciphertext check (octet_length(ciphertext) > 0 and octet_length(nonce) = 12),
    constraint webhook_endpoint_secret_overlap_shape check (
        (status = 'RETIRING' and overlap_expires_at is not null)
        or (status <> 'RETIRING' and overlap_expires_at is null)
    ),
    constraint webhook_endpoint_secret_revoked_shape check (
        (status = 'REVOKED' and revoked_at is not null)
        or (status <> 'REVOKED' and revoked_at is null)
    )
);
create unique index webhook_endpoint_secrets_one_active
    on webhook_endpoint_secrets (endpoint_id) where status = 'ACTIVE';
create unique index webhook_endpoint_secrets_one_retiring
    on webhook_endpoint_secrets (endpoint_id) where status = 'RETIRING';

create table webhook_subscriptions (
    endpoint_id uuid not null references webhook_endpoints(id),
    event_type varchar(160) not null,
    event_version integer not null,
    payload_policy varchar(30) not null,
    created_at timestamptz not null,
    primary key (endpoint_id, event_type, event_version),
    constraint webhook_subscription_event_type check (event_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'),
    constraint webhook_subscription_version check (event_version > 0),
    constraint webhook_subscription_payload_policy check (payload_policy in ('METADATA_ONLY', 'PUBLIC_CONTENT'))
);

create table webhook_deliveries (
    id uuid primary key,
    endpoint_id uuid not null references webhook_endpoints(id),
    event_id uuid not null,
    event_type varchar(160) not null,
    event_version integer not null,
    payload_checksum varchar(64) not null,
    payload_json jsonb not null,
    status varchar(30) not null,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    error_category varchar(100) null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz null,
    version bigint not null default 0,
    constraint webhook_delivery_endpoint_event_unique unique (endpoint_id, event_id),
    constraint webhook_delivery_event_type check (event_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'),
    constraint webhook_delivery_version check (event_version > 0),
    constraint webhook_delivery_checksum check (payload_checksum ~ '^[0-9a-f]{64}$'),
    constraint webhook_delivery_payload_json check (jsonb_typeof(payload_json) = 'object'),
    constraint webhook_delivery_status check (status in ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'RETRY_SCHEDULED', 'DEAD_LETTERED', 'CANCELLED')),
    constraint webhook_delivery_attempt_count check (attempt_count >= 0),
    constraint webhook_delivery_lease_shape check (
        (status = 'IN_FLIGHT' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'IN_FLIGHT' and lease_owner is null and lease_expires_at is null)
    )
);
create index webhook_deliveries_claim_idx
    on webhook_deliveries (status, next_attempt_at, created_at, id)
    where status in ('PENDING', 'RETRY_SCHEDULED');
create index webhook_deliveries_endpoint_history_idx
    on webhook_deliveries (endpoint_id, created_at desc, id desc);
create index webhook_deliveries_lease_recovery_idx
    on webhook_deliveries (lease_expires_at) where status = 'IN_FLIGHT';

create table webhook_delivery_attempts (
    id uuid primary key,
    delivery_id uuid not null references webhook_deliveries(id),
    attempt_number integer not null,
    request_timestamp timestamptz not null,
    response_status integer null,
    response_headers_json jsonb not null,
    response_summary varchar(500) null,
    latency_millis bigint null,
    error_category varchar(100) null,
    completed_at timestamptz null,
    constraint webhook_delivery_attempt_sequence_unique unique (delivery_id, attempt_number),
    constraint webhook_delivery_attempt_number check (attempt_number > 0),
    constraint webhook_delivery_attempt_headers_json check (jsonb_typeof(response_headers_json) = 'object'),
    constraint webhook_delivery_attempt_latency check (latency_millis is null or latency_millis >= 0),
    constraint webhook_delivery_attempt_response_summary_bounded check (response_summary is null or length(response_summary) <= 500)
);
create index webhook_delivery_attempts_delivery_idx
    on webhook_delivery_attempts (delivery_id, attempt_number desc);
