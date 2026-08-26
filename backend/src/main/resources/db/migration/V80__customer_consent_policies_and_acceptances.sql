create table customer_consent_policies (
    id uuid primary key,
    policy_key varchar(80) not null,
    context varchar(30) not null,
    lifecycle varchar(20) not null,
    draft_title varchar(200) not null,
    draft_document_json jsonb not null,
    draft_plain_text text not null,
    draft_checksum_sha256 char(64) not null,
    draft_required boolean not null,
    draft_display_order integer not null,
    draft_version integer not null,
    published_version integer null,
    aggregate_version bigint not null default 0,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint customer_consent_policies_context_key_unique unique (context, policy_key),
    constraint customer_consent_policies_id_context_unique unique (id, context),
    constraint customer_consent_policies_key_valid check (policy_key ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$'),
    constraint customer_consent_policies_context_valid check (context in ('REGISTRATION', 'REQUEST_SUBMISSION')),
    constraint customer_consent_policies_lifecycle_valid check (lifecycle in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    constraint customer_consent_policies_title_valid check (
        length(btrim(draft_title)) between 1 and 200
        and draft_title !~ '[[:cntrl:]<>]'
    ),
    constraint customer_consent_policies_document_valid check (jsonb_typeof(draft_document_json) = 'object'),
    constraint customer_consent_policies_plain_text_valid check (
        length(btrim(draft_plain_text)) between 1 and 500000
        and draft_plain_text !~ '[<>]'
    ),
    constraint customer_consent_policies_checksum_valid check (draft_checksum_sha256 ~ '^[0-9a-f]{64}$'),
    constraint customer_consent_policies_display_order_valid check (draft_display_order between 0 and 10000),
    constraint customer_consent_policies_draft_version_valid check (draft_version >= 1),
    constraint customer_consent_policies_aggregate_version_valid check (aggregate_version >= 0),
    constraint customer_consent_policies_timestamps_valid check (updated_at >= created_at),
    constraint customer_consent_policies_published_pointer_valid check (
        (lifecycle = 'DRAFT' and published_version is null)
        or (lifecycle in ('PUBLISHED', 'ARCHIVED') and published_version is not null)
    )
);

create table customer_consent_policy_versions (
    policy_id uuid not null references customer_consent_policies(id),
    version integer not null,
    title varchar(200) not null,
    document_json jsonb not null,
    plain_text text not null,
    checksum_sha256 char(64) not null,
    required boolean not null,
    display_order integer not null,
    effective_at timestamptz not null,
    published_by_staff_id uuid not null references staff_accounts(id),
    published_by_display varchar(100) not null,
    published_at timestamptz not null,
    primary key (policy_id, version),
    constraint customer_consent_policy_versions_version_valid check (version >= 1),
    constraint customer_consent_policy_versions_title_valid check (
        length(btrim(title)) between 1 and 200
        and title !~ '[[:cntrl:]<>]'
    ),
    constraint customer_consent_policy_versions_document_valid check (jsonb_typeof(document_json) = 'object'),
    constraint customer_consent_policy_versions_plain_text_valid check (
        length(btrim(plain_text)) between 1 and 50000
        and octet_length(plain_text) <= 200000
        and plain_text !~ '[<>]'
    ),
    constraint customer_consent_policy_versions_checksum_valid check (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    constraint customer_consent_policy_versions_display_order_valid check (display_order between 0 and 10000),
    constraint customer_consent_policy_versions_immediately_effective check (effective_at = published_at),
    constraint customer_consent_policy_versions_publisher_display_valid check (
        length(btrim(published_by_display)) between 1 and 100
        and published_by_display !~ '[[:cntrl:]<>]'
    )
);

alter table customer_consent_policies
    add constraint customer_consent_policies_published_version_fkey
    foreign key (id, published_version)
    references customer_consent_policy_versions(policy_id, version);

create index customer_consent_policies_admin_idx
    on customer_consent_policies (context, lifecycle, updated_at desc, id);

create index customer_consent_policies_current_published_idx
    on customer_consent_policies (context, published_version, id)
    where lifecycle = 'PUBLISHED' and published_version is not null;

create index customer_consent_policy_versions_public_order_idx
    on customer_consent_policy_versions (display_order, policy_id, version);

create or replace function reject_customer_consent_policy_identity_change()
returns trigger
language plpgsql
as $$
begin
    if old.policy_key is distinct from new.policy_key or old.context is distinct from new.context then
        raise exception 'Customer consent policy key and context are immutable';
    end if;
    return new;
end;
$$;

create trigger customer_consent_policy_identity_immutable
before update of policy_key, context on customer_consent_policies
for each row execute function reject_customer_consent_policy_identity_change();

alter table customer_accounts
    add constraint customer_accounts_id_customer_id_unique
    unique (id, customer_id);

alter table tickets
    add constraint tickets_id_requester_id_unique
    unique (id, requester_id);

create table customer_consent_acceptances (
    id uuid primary key,
    customer_id uuid not null references customers(id),
    account_id uuid null,
    ticket_id uuid null,
    policy_id uuid not null,
    policy_version integer not null,
    context varchar(30) not null,
    accepted_at timestamptz not null,
    source varchar(40) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    constraint customer_consent_acceptances_account_customer_fkey
        foreign key (account_id, customer_id)
        references customer_accounts(id, customer_id),
    constraint customer_consent_acceptances_ticket_customer_fkey
        foreign key (ticket_id, customer_id)
        references tickets(id, requester_id),
    constraint customer_consent_acceptances_policy_context_fkey
        foreign key (policy_id, context)
        references customer_consent_policies(id, context),
    constraint customer_consent_acceptances_policy_version_fkey
        foreign key (policy_id, policy_version)
        references customer_consent_policy_versions(policy_id, version),
    constraint customer_consent_acceptances_policy_version_valid check (policy_version >= 1),
    constraint customer_consent_acceptances_context_valid check (context in ('REGISTRATION', 'REQUEST_SUBMISSION')),
    constraint customer_consent_acceptances_source_valid check (source = 'CUSTOMER_PORTAL'),
    constraint customer_consent_acceptances_request_context_valid check (
        length(btrim(request_id)) between 1 and 100
        and request_id !~ '[[:cntrl:]<>]'
        and length(btrim(correlation_id)) between 1 and 100
        and correlation_id !~ '[[:cntrl:]<>]'
    ),
    constraint customer_consent_acceptances_resource_valid check (
        (context = 'REGISTRATION' and account_id is not null and ticket_id is null)
        or (context = 'REQUEST_SUBMISSION' and ticket_id is not null)
    )
);

create unique index customer_consent_acceptances_registration_unique
    on customer_consent_acceptances (account_id, policy_id, policy_version)
    where context = 'REGISTRATION';

create unique index customer_consent_acceptances_request_unique
    on customer_consent_acceptances (ticket_id, policy_id, policy_version)
    where context = 'REQUEST_SUBMISSION';

create index customer_consent_acceptances_customer_idx
    on customer_consent_acceptances (customer_id, accepted_at desc, id);

create or replace function reject_customer_consent_policy_version_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Customer consent policy versions are immutable';
end;
$$;

create trigger customer_consent_policy_versions_immutable
before update or delete on customer_consent_policy_versions
for each row execute function reject_customer_consent_policy_version_mutation();

create or replace function reject_customer_consent_acceptance_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Customer consent acceptances are append-only';
end;
$$;

create trigger customer_consent_acceptances_append_only
before update or delete on customer_consent_acceptances
for each row execute function reject_customer_consent_acceptance_mutation();
