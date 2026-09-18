alter table ai_cost_ledger
    add column overrun_microusd bigint not null default 0,
    add constraint ai_cost_overrun_nonnegative check (overrun_microusd >= 0);

create table ai_provider_calls (
    call_id uuid primary key,
    reservation_id uuid not null unique references ai_cost_ledger(reservation_id),
    operation_key varchar(200) not null unique,
    job_id uuid null,
    stage varchar(24) not null,
    lifecycle_status varchar(20) not null,
    settlement_status varchar(20) not null,
    requested_alias varchar(100) not null,
    actual_model varchar(160) null,
    provider_request_id varchar(200) null,
    usage_schema_version varchar(40) null,
    usage_status varchar(20) null,
    usage_issue_code varchar(80) null,
    input_uncached_tokens bigint null,
    input_cache_read_tokens bigint null,
    input_cache_write_tokens bigint null,
    output_billed_tokens bigint null,
    pricing_version varchar(40) not null,
    service_tier varchar(24) not null,
    context_price_band varchar(24) not null,
    known_cost_microusd bigint null,
    overrun_microusd bigint not null default 0,
    receipt_fingerprint char(64) null,
    created_at timestamptz not null,
    dispatching_at timestamptz null,
    responded_at timestamptz null,
    updated_at timestamptz not null,
    constraint ai_provider_call_stage_valid check (
        stage in ('GENERATION', 'QUERY_EMBEDDING', 'INDEX_EMBEDDING')
    ),
    constraint ai_provider_call_lifecycle_valid check (
        lifecycle_status in ('RESERVED', 'DISPATCHING', 'RESPONDED', 'UNKNOWN')
    ),
    constraint ai_provider_call_settlement_valid check (
        settlement_status in ('PENDING', 'SETTLED', 'UNKNOWN', 'CONFLICT')
    ),
    constraint ai_provider_call_usage_status_valid check (
        usage_status is null or usage_status in ('KNOWN', 'UNAVAILABLE', 'INCONSISTENT')
    ),
    constraint ai_provider_call_usage_values_valid check (
        (usage_status = 'KNOWN'
            and input_uncached_tokens >= 0
            and input_cache_read_tokens >= 0
            and input_cache_write_tokens >= 0
            and output_billed_tokens >= 0)
        or (usage_status is distinct from 'KNOWN'
            and input_uncached_tokens is null
            and input_cache_read_tokens is null
            and input_cache_write_tokens is null
            and output_billed_tokens is null)
    ),
    constraint ai_provider_call_cost_values_valid check (
        (known_cost_microusd is null or known_cost_microusd >= 0)
        and overrun_microusd >= 0
    ),
    constraint ai_provider_call_settlement_shape check (
        (settlement_status = 'PENDING' and known_cost_microusd is null)
        or (settlement_status = 'SETTLED' and known_cost_microusd is not null)
        or (settlement_status = 'UNKNOWN' and known_cost_microusd is null)
        or settlement_status = 'CONFLICT'
    ),
    constraint ai_provider_call_response_shape check (
        (lifecycle_status in ('RESERVED', 'DISPATCHING')
            and responded_at is null
            and receipt_fingerprint is null
            and usage_status is null)
        or (lifecycle_status = 'UNKNOWN'
            and responded_at is null
            and receipt_fingerprint is null
            and usage_status is null)
        or (lifecycle_status = 'RESPONDED'
            and responded_at is not null
            and receipt_fingerprint is not null
            and usage_status is not null)
    )
);

create index ai_provider_calls_job_idx on ai_provider_calls (job_id, created_at, call_id);
create index ai_provider_calls_unsettled_idx
    on ai_provider_calls (settlement_status, updated_at, call_id)
    where settlement_status in ('PENDING', 'UNKNOWN', 'CONFLICT');
