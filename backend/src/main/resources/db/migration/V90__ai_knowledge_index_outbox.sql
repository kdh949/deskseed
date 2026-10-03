create table ai_knowledge_index_outbox (
    event_id uuid primary key,
    workspace_key varchar(80) not null,
    article_id uuid not null,
    revision_id uuid not null,
    action varchar(16) not null,
    public_revision char(64) not null,
    payload_json jsonb not null,
    payload_checksum char(64) not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    delivered_at timestamptz null,
    created_at timestamptz not null,
    last_error_code varchar(80) null,
    constraint ai_kb_outbox_workspace_bounded check (
        length(btrim(workspace_key)) between 1 and 80 and workspace_key !~ '[[:cntrl:]]'
    ),
    constraint ai_kb_outbox_action_valid check (action in ('UPSERT', 'DELETE')),
    constraint ai_kb_outbox_hashes_valid check (
        public_revision ~ '^[0-9a-f]{64}$' and payload_checksum ~ '^[0-9a-f]{64}$'
    ),
    constraint ai_kb_outbox_payload_object check (jsonb_typeof(payload_json) = 'object'),
    constraint ai_kb_outbox_status_valid check (status in ('PENDING', 'LEASED', 'DELIVERED', 'DEAD')),
    constraint ai_kb_outbox_attempts_nonnegative check (attempts >= 0),
    constraint ai_kb_outbox_lease_shape check (
        (status = 'LEASED' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'LEASED' and lease_owner is null and lease_expires_at is null)
    ),
    constraint ai_kb_outbox_delivery_shape check (
        (status = 'DELIVERED' and delivered_at is not null)
        or (status <> 'DELIVERED' and delivered_at is null)
    ),
    constraint ai_kb_outbox_revision_action_unique unique (workspace_key, article_id, revision_id, action)
);

create index ai_kb_outbox_dispatch_idx
    on ai_knowledge_index_outbox (available_at, created_at, event_id)
    where status = 'PENDING';
create index ai_kb_outbox_lease_idx
    on ai_knowledge_index_outbox (lease_expires_at, event_id)
    where status = 'LEASED';
