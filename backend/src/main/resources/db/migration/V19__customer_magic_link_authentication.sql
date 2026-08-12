alter table admin_security_audit_events
    drop constraint admin_security_actor_type_valid,
    drop constraint admin_security_source_valid;

alter table admin_security_audit_events
    add constraint admin_security_actor_type_valid check (
        actor_type in ('CUSTOMER', 'STAFF', 'SYSTEM')
    ),
    add constraint admin_security_source_valid check (
        source in ('CUSTOMER_PORTAL', 'AGENT_UI', 'ADMIN_UI', 'SYSTEM_JOB')
    );

create table customer_accounts (
    id uuid primary key,
    customer_id uuid not null unique references customers(id),
    email_normalized varchar(254) not null unique,
    status varchar(20) not null,
    verified_at timestamptz not null,
    last_login_at timestamptz not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint customer_account_status_valid check (status in ('ACTIVE', 'DISABLED')),
    constraint customer_account_email_bounded check (
        length(email_normalized) between 3 and 254
        and email_normalized = lower(email_normalized)
    )
);

create table customer_magic_link_tokens (
    id uuid primary key,
    token_digest char(64) not null unique,
    email_normalized varchar(254) not null,
    email_display varchar(254) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    consumed_at timestamptz null,
    constraint customer_magic_link_digest_hex check (token_digest ~ '^[0-9a-f]{64}$'),
    constraint customer_magic_link_expiry_after_create check (expires_at > created_at),
    constraint customer_magic_link_consumed_after_create check (
        consumed_at is null or consumed_at >= created_at
    )
);

create index customer_magic_link_tokens_cleanup_idx
    on customer_magic_link_tokens (coalesce(consumed_at, expires_at), id);

create table customer_magic_link_request_limits (
    destination_fingerprint char(64) not null,
    network_fingerprint char(64) not null,
    request_count integer not null,
    window_started_at timestamptz not null,
    locked_until timestamptz null,
    updated_at timestamptz not null,
    primary key (destination_fingerprint, network_fingerprint),
    constraint customer_magic_link_limit_fingerprints_hex check (
        destination_fingerprint ~ '^[0-9a-f]{64}$'
        and network_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    constraint customer_magic_link_request_count_valid check (request_count >= 0)
);

create index customer_magic_link_request_limits_cleanup_idx
    on customer_magic_link_request_limits (updated_at);

create table customer_sessions (
    id uuid primary key,
    account_id uuid not null references customer_accounts(id),
    session_token_digest char(64) not null unique,
    created_at timestamptz not null,
    last_activity_at timestamptz not null,
    expires_at timestamptz not null,
    absolute_expires_at timestamptz not null,
    revoked_at timestamptz null,
    constraint customer_session_digest_hex check (session_token_digest ~ '^[0-9a-f]{64}$'),
    constraint customer_session_expiry_valid check (
        expires_at > created_at and absolute_expires_at >= expires_at
    ),
    constraint customer_session_revocation_valid check (
        revoked_at is null or revoked_at >= created_at
    )
);

create index customer_sessions_active_account_idx
    on customer_sessions (account_id, absolute_expires_at desc)
    where revoked_at is null;

create index customer_sessions_cleanup_idx
    on customer_sessions (coalesce(revoked_at, absolute_expires_at), id);

alter table outbound_mail_intents
    add column protected_body_ciphertext bytea null,
    add column protected_body_nonce bytea null,
    add column protected_body_key_version varchar(40) null,
    add constraint outbound_mail_protected_body_complete check (
        (protected_body_ciphertext is null and protected_body_nonce is null and protected_body_key_version is null)
        or
        (protected_body_ciphertext is not null and protected_body_nonce is not null and protected_body_key_version is not null)
    );
