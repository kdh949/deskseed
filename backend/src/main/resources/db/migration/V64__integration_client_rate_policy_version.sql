alter table integration_clients
    add column rate_policy_version bigint not null default 0,
    add constraint integration_client_rate_policy_version_valid
        check (rate_policy_version >= 0);
