create table ai_knowledge_manifest_snapshots (
    snapshot_token uuid primary key,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    constraint ai_knowledge_manifest_snapshot_expiry check (expires_at > created_at)
);

create index ai_knowledge_manifest_snapshots_expiry_idx
    on ai_knowledge_manifest_snapshots (expires_at, snapshot_token);

create table ai_knowledge_manifest_snapshot_items (
    snapshot_token uuid not null references ai_knowledge_manifest_snapshots(snapshot_token) on delete cascade,
    article_id uuid not null,
    revision_id uuid not null,
    public_revision char(64) not null,
    published_at timestamptz not null,
    primary key (snapshot_token, article_id),
    constraint ai_knowledge_manifest_item_revision_shape check (public_revision ~ '^[0-9a-f]{64}$')
);
