alter table access_audit_events
    add column session_fingerprint varchar(100) null,
    add column auth_type varchar(40) null,
    add column origin_search_event_id uuid null;

alter table access_audit_events
    drop constraint access_audit_action_valid;

alter table access_audit_events
    add constraint access_audit_action_valid check (
        action in ('TICKET_VIEWED', 'SEARCH_EXECUTED', 'SEARCH_RESULT_OPENED', 'API_RESOURCE_READ')
    ),
    add constraint access_audit_origin_search_fk
        foreign key (origin_search_event_id) references access_audit_events (id),
    add constraint access_audit_search_shape_valid check (
        (action = 'SEARCH_EXECUTED'
            and resource_type = 'SEARCH'
            and resource_id is null
            and ticket_number is null
            and origin_search_event_id is null)
        or
        (action = 'SEARCH_RESULT_OPENED'
            and resource_type = 'TICKET'
            and resource_id is not null
            and ticket_number is not null
            and origin_search_event_id is not null)
        or
        action in ('TICKET_VIEWED', 'API_RESOURCE_READ')
    );

alter table access_audit_events
    alter column resource_id drop not null;

create unique index access_audit_search_result_open_unique
    on access_audit_events (actor_id, resource_id, interaction_id, action)
    where action = 'SEARCH_RESULT_OPENED' and outcome = 'SUCCEEDED';

create index access_audit_origin_search_idx
    on access_audit_events (origin_search_event_id, occurred_at desc, id desc)
    where origin_search_event_id is not null;

create table search_audit_details (
    access_event_id uuid primary key references access_audit_events (id),
    query_redacted varchar(500) not null,
    query_fingerprint varchar(100) not null,
    query_key_version varchar(64) not null,
    normalized_filters jsonb not null,
    sort varchar(100) not null,
    result_count bigint not null,
    constraint search_audit_result_count_valid check (result_count >= 0),
    constraint search_audit_filters_object_valid check (jsonb_typeof(normalized_filters) = 'object')
);

create index search_audit_fingerprint_idx
    on search_audit_details (query_fingerprint, access_event_id);

create table search_audit_result_items (
    access_event_id uuid not null references access_audit_events (id),
    ticket_id uuid not null,
    ticket_number bigint not null,
    result_ordinal integer not null,
    primary key (access_event_id, ticket_id),
    constraint search_audit_result_ordinal_unique unique (access_event_id, result_ordinal),
    constraint search_audit_result_ticket_number_positive check (ticket_number > 0),
    constraint search_audit_result_ordinal_nonnegative check (result_ordinal >= 0)
);

create index search_audit_result_ticket_idx
    on search_audit_result_items (ticket_id, access_event_id);

create trigger search_audit_result_items_immutable
before update or delete on search_audit_result_items
for each row execute function reject_access_audit_mutation();

create table search_audit_query_ciphertexts (
    access_event_id uuid primary key references access_audit_events (id),
    key_version varchar(64) not null,
    query_ciphertext bytea not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    constraint search_audit_ciphertext_nonempty check (octet_length(query_ciphertext) > 28),
    constraint search_audit_ciphertext_expiry_valid check (expires_at > created_at)
);

create index search_audit_ciphertext_expiry_idx
    on search_audit_query_ciphertexts (expires_at, access_event_id);

create trigger search_audit_details_immutable
before update or delete on search_audit_details
for each row execute function reject_access_audit_mutation();

create or replace function reject_search_audit_ciphertext_update()
returns trigger
language plpgsql
as $$
begin
    raise exception 'Search audit ciphertext is immutable until retention deletion';
end;
$$;

create trigger search_audit_query_ciphertexts_immutable
before update on search_audit_query_ciphertexts
for each row execute function reject_search_audit_ciphertext_update();
