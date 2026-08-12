alter table system_settings
    add column version bigint not null default 0;

create table customer_request_claim_grants (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    token_digest varchar(64) not null unique,
    email_fingerprint varchar(64) not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    consumed_at timestamptz null,
    constraint customer_claim_grant_digest_hex check (token_digest ~ '^[0-9a-f]{64}$'),
    constraint customer_claim_grant_email_fingerprint_hex check (email_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint customer_claim_grant_expiry_valid check (expires_at > created_at),
    constraint customer_claim_grant_consumed_valid check (consumed_at is null or consumed_at >= created_at)
);

create index customer_request_claim_grants_cleanup_idx
    on customer_request_claim_grants (coalesce(consumed_at, expires_at), id);

create index tickets_customer_portal_idx
    on tickets (requester_id, updated_at desc, ticket_number desc)
    where kind = 'CUSTOMER_REQUEST';

create index ticket_audits_customer_command_replay_idx
    on ticket_audits (actor_id, command_id, created_at, id)
    where actor_type = 'CUSTOMER'
      and actor_id is not null;
