alter table tickets
    alter column requester_id drop not null,
    drop constraint ticket_kind_valid,
    add constraint ticket_kind_valid check (
        kind in ('CUSTOMER_REQUEST', 'INTERNAL_CHILD', 'AGENT_CREATED', 'INTERNAL_WORK_ITEM')
    ),
    add constraint ticket_requester_shape check (
        (kind = 'INTERNAL_WORK_ITEM' and requester_id is null)
        or (kind <> 'INTERNAL_WORK_ITEM' and requester_id is not null)
    );

alter table ticket_comments
    drop constraint comment_author_type_valid,
    add constraint comment_author_type_valid check (
        author_type in ('CUSTOMER', 'AGENT', 'INTEGRATION_CLIENT', 'SYSTEM', 'AUTOMATION')
    );

create table platform_idempotency_records (
    id uuid primary key,
    client_id uuid not null references integration_clients(id),
    operation_id varchar(80) not null,
    idempotency_key_hash varchar(64) not null,
    request_hash varchar(64) not null,
    status varchar(20) not null,
    response_status integer null,
    response_headers_json text null,
    response_body_json text null,
    resource_id uuid null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    constraint platform_idempotency_identity_unique
        unique (client_id, operation_id, idempotency_key_hash),
    constraint platform_idempotency_hash_shape check (
        idempotency_key_hash ~ '^[0-9a-f]{64}$' and request_hash ~ '^[0-9a-f]{64}$'
    ),
    constraint platform_idempotency_status_valid check (status in ('IN_PROGRESS', 'SUCCEEDED')),
    constraint platform_idempotency_response_shape check (
        (status = 'IN_PROGRESS' and response_status is null and response_headers_json is null and response_body_json is null)
        or
        (status = 'SUCCEEDED' and response_status between 200 and 299
            and response_headers_json is json object and response_body_json is json)
    ),
    constraint platform_idempotency_expiry_after_creation check (expires_at > created_at)
);

create index platform_idempotency_expiry_idx
    on platform_idempotency_records (expires_at, id);

create index platform_idempotency_resource_idx
    on platform_idempotency_records (client_id, resource_id)
    where resource_id is not null;

