create table ai_settings (
    singleton boolean primary key default true,
    enabled boolean not null default false,
    summary_enabled boolean not null default false,
    triage_enabled boolean not null default false,
    reply_draft_enabled boolean not null default false,
    fast_model_alias varchar(100) not null default 'openai/gpt-5.6-luna',
    standard_model_alias varchar(100) not null default 'openai/gpt-5.6-terra',
    version bigint not null default 0,
    updated_by_staff_id uuid null,
    updated_at timestamptz not null,
    constraint ai_settings_singleton check (singleton),
    constraint ai_settings_version_nonnegative check (version >= 0),
    constraint ai_settings_aliases_bounded check (
        length(fast_model_alias) between 1 and 100 and fast_model_alias !~ '[[:cntrl:]]'
        and length(standard_model_alias) between 1 and 100 and standard_model_alias !~ '[[:cntrl:]]'
    )
);

insert into ai_settings (singleton, updated_at) values (true, clock_timestamp());

create table ai_feature_staff_allowlist (
    staff_id uuid primary key references staff_accounts(id),
    added_by_staff_id uuid not null references staff_accounts(id),
    added_at timestamptz not null
);

create table ai_admin_operations (
    operation_id uuid primary key,
    operation_type varchar(32) not null,
    request_fingerprint char(64) not null,
    requested_by_staff_id uuid not null references staff_accounts(id),
    item_count integer not null,
    created_at timestamptz not null,
    constraint ai_admin_operation_type_valid check (operation_type = 'KB_REINDEX'),
    constraint ai_admin_operation_fingerprint_valid check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ai_admin_operation_count_nonnegative check (item_count >= 0)
);
