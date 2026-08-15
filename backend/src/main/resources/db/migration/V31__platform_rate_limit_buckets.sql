create table platform_rate_limit_buckets (
    client_id uuid not null,
    window_started_at timestamptz not null,
    request_count integer not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (client_id, window_started_at),
    constraint platform_rate_limit_buckets_count_positive check (request_count > 0),
    constraint platform_rate_limit_buckets_expiry_valid check (expires_at > window_started_at)
);

create index platform_rate_limit_buckets_expiry_idx
    on platform_rate_limit_buckets (expires_at, client_id, window_started_at);
