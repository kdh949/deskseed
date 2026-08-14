create table public_request_rate_limit_buckets (
    bucket_type varchar(20) not null,
    bucket_fingerprint varchar(64) not null,
    window_started_at timestamptz not null,
    request_count integer not null,
    expires_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (bucket_type, bucket_fingerprint, window_started_at),
    constraint public_request_rate_limit_buckets_type_check
        check (bucket_type in ('GLOBAL', 'CLIENT', 'DESTINATION')),
    constraint public_request_rate_limit_buckets_fingerprint_check
        check (bucket_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint public_request_rate_limit_buckets_count_check
        check (request_count > 0),
    constraint public_request_rate_limit_buckets_expiry_check
        check (expires_at > window_started_at)
);

create index public_request_rate_limit_buckets_expiry_idx
    on public_request_rate_limit_buckets (expires_at, bucket_type, bucket_fingerprint, window_started_at);
