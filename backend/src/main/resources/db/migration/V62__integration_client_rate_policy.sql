alter table integration_clients
    add column rate_limit_per_minute integer not null default 60,
    add column usage_count bigint not null default 0,
    add constraint integration_client_rate_limit_valid
        check (rate_limit_per_minute between 1 and 10000),
    add constraint integration_client_usage_count_valid
        check (usage_count >= 0);
