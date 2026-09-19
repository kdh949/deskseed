alter table ai_cost_ledger drop constraint ai_cost_call_type_valid;
alter table ai_cost_ledger add constraint ai_cost_call_type_valid check (
    call_type in ('GENERATION', 'CONTEXT_MEMORY', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING')
);

alter table ai_provider_calls drop constraint ai_provider_call_stage_valid;
alter table ai_provider_calls add constraint ai_provider_call_stage_valid check (
    stage in ('GENERATION', 'CONTEXT_MEMORY', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING')
);

alter table ai_shared_executions drop constraint ai_shared_execution_phase_valid;
alter table ai_shared_executions add constraint ai_shared_execution_phase_valid check (
    phase in ('AUTHORIZE', 'READY', 'QUERY_EMBEDDING', 'CONTEXT_MEMORY', 'GENERATION', 'COMPLETE')
);

create table ai_context_memories (
    memory_id uuid primary key,
    workspace_key varchar(80) not null,
    requester_id uuid not null,
    ticket_id uuid not null,
    memory_version integer not null,
    status varchar(16) not null,
    covered_through_sequence integer not null,
    source_prefix_digest char(64) not null,
    schema_version varchar(40) not null,
    policy_version varchar(40) not null,
    prompt_version varchar(80) not null,
    model_alias varchar(100) not null,
    update_count integer not null,
    payload_ciphertext bytea not null,
    payload_nonce bytea not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    invalidated_at timestamptz null,
    invalidation_reason varchar(40) null,
    constraint ai_context_memory_version_positive check (memory_version > 0),
    constraint ai_context_memory_status_valid check (status in ('ACTIVE', 'INVALIDATED')),
    constraint ai_context_memory_coverage_positive check (covered_through_sequence > 0),
    constraint ai_context_memory_source_digest_shape check (source_prefix_digest ~ '^[0-9a-f]{64}$'),
    constraint ai_context_memory_schema_version check (schema_version = 'context-memory-v1'),
    constraint ai_context_memory_policy_version check (policy_version = 'context-memory-policy-v1'),
    constraint ai_context_memory_update_count_nonnegative check (update_count >= 0),
    constraint ai_context_memory_expiry check (expires_at > created_at),
    constraint ai_context_memory_invalidation_shape check (
        (status = 'ACTIVE' and invalidated_at is null and invalidation_reason is null)
        or (status = 'INVALIDATED' and invalidated_at is not null and invalidation_reason is not null)
    )
);

create unique index ai_context_memory_one_active_scope_idx
    on ai_context_memories (workspace_key, requester_id, ticket_id)
    where status = 'ACTIVE';

create index ai_context_memory_retention_idx
    on ai_context_memories (expires_at, memory_id);
