alter table customers
    add column company_name varchar(160) null,
    add constraint customers_company_name_valid check (
        company_name is null
        or (
            length(btrim(company_name)) between 1 and 160
            and company_name !~ '[[:cntrl:]<>]'
        )
    );

alter table customer_accounts
    add column password_hash varchar(255) null,
    add column password_changed_at timestamptz null,
    add column credential_version bigint not null default 0,
    add constraint customer_accounts_password_state_valid check (
        (password_hash is null and password_changed_at is null)
        or (
            password_hash like '$argon2id$%'
            and length(password_hash) between 20 and 255
            and password_hash !~ '[[:cntrl:]]'
            and password_changed_at is not null
            and password_changed_at >= created_at
        )
    ),
    add constraint customer_accounts_credential_version_valid check (credential_version >= 0);

alter table customer_sessions
    add column authentication_method varchar(30) not null default 'MAGIC_LINK',
    add column credential_version_snapshot bigint not null default 0,
    add constraint customer_sessions_authentication_method_valid check (
        authentication_method in ('MAGIC_LINK', 'PASSWORD')
    ),
    add constraint customer_sessions_credential_version_valid check (credential_version_snapshot >= 0);

create table customer_registration_intents (
    id uuid primary key,
    email_normalized varchar(254) not null,
    email_display varchar(254) not null,
    password_hash varchar(255) not null,
    display_name varchar(100) not null,
    company_name varchar(160) not null,
    continuation_secret_digest char(64) not null unique,
    status varchar(20) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    consumed_at timestamptz null,
    cancelled_at timestamptz null,
    version bigint not null default 0,
    constraint customer_registration_intents_email_valid check (
        length(email_normalized) between 3 and 254
        and email_normalized = lower(email_normalized)
        and email_normalized !~ '[[:cntrl:]<>]'
        and length(btrim(email_display)) between 3 and 254
        and email_display !~ '[[:cntrl:]<>]'
    ),
    constraint customer_registration_intents_password_hash_valid check (
        password_hash like '$argon2id$%'
        and length(password_hash) between 20 and 255
        and password_hash !~ '[[:cntrl:]]'
    ),
    constraint customer_registration_intents_profile_valid check (
        length(btrim(display_name)) between 1 and 100
        and display_name !~ '[[:cntrl:]<>]'
        and length(btrim(company_name)) between 1 and 160
        and company_name !~ '[[:cntrl:]<>]'
    ),
    constraint customer_registration_intents_continuation_digest_valid check (
        continuation_secret_digest ~ '^[0-9a-f]{64}$'
    ),
    constraint customer_registration_intents_status_valid check (
        (status = 'PENDING' and consumed_at is null and cancelled_at is null)
        or (status = 'CONSUMED' and consumed_at is not null and cancelled_at is null)
        or (status = 'CANCELLED' and consumed_at is null and cancelled_at is not null)
    ),
    constraint customer_registration_intents_request_context_valid check (
        length(btrim(request_id)) between 1 and 100
        and request_id !~ '[[:cntrl:]<>]'
        and length(btrim(correlation_id)) between 1 and 100
        and correlation_id !~ '[[:cntrl:]<>]'
    ),
    constraint customer_registration_intents_timestamps_valid check (
        updated_at >= created_at
        and expires_at > created_at
        and (consumed_at is null or consumed_at >= created_at)
        and (cancelled_at is null or cancelled_at >= created_at)
    ),
    constraint customer_registration_intents_version_valid check (version >= 0)
);

create unique index customer_registration_intents_pending_email_unique
    on customer_registration_intents (email_normalized)
    where status = 'PENDING';

create index customer_registration_intents_expiry_idx
    on customer_registration_intents (expires_at, id)
    where status = 'PENDING';

create index customer_registration_intents_cleanup_idx
    on customer_registration_intents (coalesce(consumed_at, cancelled_at, expires_at), id);

create table customer_registration_intent_consents (
    intent_id uuid not null references customer_registration_intents(id),
    policy_id uuid not null,
    policy_version integer not null,
    context varchar(30) not null default 'REGISTRATION',
    selected_at timestamptz not null,
    primary key (intent_id, policy_id),
    constraint customer_registration_intent_consents_context_valid check (context = 'REGISTRATION'),
    constraint customer_registration_intent_consents_version_valid check (policy_version >= 1),
    constraint customer_registration_intent_consents_policy_context_fkey
        foreign key (policy_id, context)
        references customer_consent_policies(id, context),
    constraint customer_registration_intent_consents_policy_version_fkey
        foreign key (policy_id, policy_version)
        references customer_consent_policy_versions(policy_id, version)
);

create index customer_registration_intent_consents_policy_idx
    on customer_registration_intent_consents (policy_id, policy_version, intent_id);

alter table customer_magic_link_tokens
    rename to customer_one_time_tokens;

alter table customer_one_time_tokens
    rename constraint customer_magic_link_tokens_pkey to customer_one_time_tokens_pkey;

alter table customer_one_time_tokens
    rename constraint customer_magic_link_tokens_token_digest_key to customer_one_time_tokens_token_digest_key;

alter table customer_one_time_tokens
    rename constraint customer_magic_link_digest_hex to customer_one_time_tokens_digest_hex;

alter table customer_one_time_tokens
    rename constraint customer_magic_link_expiry_after_create to customer_one_time_tokens_expiry_after_create;

alter table customer_one_time_tokens
    rename constraint customer_magic_link_consumed_after_create to customer_one_time_tokens_consumed_after_create;

alter index customer_magic_link_tokens_cleanup_idx
    rename to customer_one_time_tokens_cleanup_idx;

alter table customer_one_time_tokens
    add column purpose varchar(30) not null default 'PASSWORDLESS_LOGIN',
    add column registration_intent_id uuid null references customer_registration_intents(id),
    add column account_id uuid null references customer_accounts(id),
    add constraint customer_one_time_tokens_purpose_valid check (
        purpose in ('PASSWORDLESS_LOGIN', 'EMAIL_VERIFICATION', 'PASSWORD_RESET')
    ),
    add constraint customer_one_time_tokens_purpose_resource_valid check (
        (purpose = 'PASSWORDLESS_LOGIN' and registration_intent_id is null and account_id is null)
        or (purpose = 'EMAIL_VERIFICATION' and registration_intent_id is not null and account_id is null)
        or (purpose = 'PASSWORD_RESET' and registration_intent_id is null and account_id is not null)
    );

create index customer_one_time_tokens_purpose_cleanup_idx
    on customer_one_time_tokens (purpose, coalesce(consumed_at, expires_at), id);

create index customer_one_time_tokens_registration_intent_idx
    on customer_one_time_tokens (registration_intent_id, expires_at desc, id)
    where registration_intent_id is not null and consumed_at is null;

create index customer_one_time_tokens_account_idx
    on customer_one_time_tokens (account_id, purpose, expires_at desc, id)
    where account_id is not null and consumed_at is null;

drop table customer_magic_link_request_limits;
