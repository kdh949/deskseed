create table integration_clients (
    id uuid primary key,
    name varchar(100) not null,
    description varchar(500) not null default '',
    status varchar(20) not null,
    scopes_json text not null,
    resource_constraints_json text not null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    last_used_at timestamptz null,
    last_used_ip varchar(64) null,
    version bigint not null default 0,
    constraint integration_client_name_not_blank check (length(btrim(name)) > 0),
    constraint integration_client_status_valid check (status in ('ACTIVE', 'DISABLED', 'REVOKED')),
    constraint integration_client_scopes_json check (scopes_json is json array),
    constraint integration_client_scopes_supported check (
        jsonb_array_length(scopes_json::jsonb) between 1 and 4
        and scopes_json::jsonb <@ '["tickets:create", "tickets:read", "tickets:update", "tickets:comment:internal"]'::jsonb
    ),
    constraint integration_client_constraints_json check (resource_constraints_json is json object)
);

create unique index integration_clients_name_ci_unique
    on integration_clients (lower(btrim(name)));
create index integration_clients_status_created_idx
    on integration_clients (status, created_at desc, id);

create table integration_credentials (
    id uuid primary key,
    client_id uuid not null references integration_clients(id),
    sequence integer not null,
    public_key_id varchar(32) not null,
    secret_hash varchar(512) not null,
    status varchar(20) not null,
    expires_at timestamptz not null,
    overlap_expires_at timestamptz null,
    rotated_from_credential_id uuid null references integration_credentials(id),
    created_by_staff_id uuid not null references staff_accounts(id),
    created_at timestamptz not null,
    revoked_at timestamptz null,
    last_used_at timestamptz null,
    last_used_ip varchar(64) null,
    version bigint not null default 0,
    constraint integration_credential_sequence_positive check (sequence > 0),
    constraint integration_credential_public_id_not_blank check (length(btrim(public_key_id)) >= 16),
    constraint integration_credential_secret_hash_not_blank check (length(btrim(secret_hash)) >= 32),
    constraint integration_credential_status_valid check (status in ('ACTIVE', 'RETIRING', 'REVOKED')),
    constraint integration_credential_expiry_after_creation check (expires_at > created_at),
    constraint integration_credential_overlap_shape check (
        (status = 'RETIRING' and overlap_expires_at is not null)
        or (status <> 'RETIRING' and overlap_expires_at is null)
    ),
    constraint integration_credential_revocation_shape check (
        (status = 'REVOKED' and revoked_at is not null)
        or (status <> 'REVOKED' and revoked_at is null)
    ),
    constraint integration_credential_sequence_unique unique (client_id, sequence),
    constraint integration_credential_public_key_unique unique (public_key_id)
);

create unique index integration_credentials_one_active_per_client
    on integration_credentials (client_id)
    where status = 'ACTIVE';
create unique index integration_credentials_one_retiring_per_client
    on integration_credentials (client_id)
    where status = 'RETIRING';
create index integration_credentials_client_created_idx
    on integration_credentials (client_id, created_at desc, id);

alter table admin_security_audit_events
    drop constraint admin_security_actor_type_valid,
    add constraint admin_security_actor_type_valid
        check (actor_type in ('CUSTOMER', 'STAFF', 'INTEGRATION_CLIENT', 'SYSTEM')),
    drop constraint admin_security_source_valid,
    add constraint admin_security_source_valid
        check (source in ('CUSTOMER_PORTAL', 'AGENT_UI', 'ADMIN_UI', 'PLATFORM_API', 'SYSTEM_JOB'));
