create table external_systems (
    id uuid primary key,
    system_key varchar(64) not null,
    display_name varchar(100) not null,
    status varchar(20) not null,
    allowed_hostnames_json text not null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint external_system_key_shape check (system_key ~ '^[a-z]([a-z0-9-]{0,62}[a-z0-9])?$'),
    constraint external_system_display_name_not_blank check (length(btrim(display_name)) between 1 and 100),
    constraint external_system_status_valid check (status in ('ACTIVE', 'DISABLED')),
    constraint external_system_allowed_hosts_json check (
        allowed_hostnames_json is json array
        and jsonb_array_length(allowed_hostnames_json::jsonb) between 1 and 20
        and octet_length(allowed_hostnames_json) <= 2048
    )
);

create unique index external_systems_system_key_unique on external_systems (system_key);
create index external_systems_status_name_idx on external_systems (status, lower(display_name), id);

create table external_references (
    id uuid primary key,
    ticket_id uuid not null references tickets(id),
    external_system_id uuid not null references external_systems(id),
    object_type varchar(30) not null,
    external_id varchar(200) not null,
    display_label varchar(200) not null,
    safe_deep_link varchar(2048) not null,
    metadata_snapshot_json text not null default '{}',
    metadata_observed_at timestamptz not null,
    created_by_actor_type varchar(30) not null,
    created_by_actor_id uuid not null,
    created_by_actor_display varchar(100) not null,
    created_at timestamptz not null,
    constraint external_reference_object_type_valid check (
        object_type in ('ORDER', 'PAYMENT', 'REFUND', 'USER', 'STORE', 'OPS_CASE', 'CUSTOM')
    ),
    constraint external_reference_external_id_not_blank check (length(btrim(external_id)) between 1 and 200),
    constraint external_reference_display_label_not_blank check (length(btrim(display_label)) between 1 and 200),
    constraint external_reference_deep_link_https check (
        length(btrim(safe_deep_link)) between 1 and 2048
        and safe_deep_link ~ '^https://'
    ),
    constraint external_reference_metadata_json check (
        metadata_snapshot_json is json object
        and octet_length(metadata_snapshot_json) <= 2048
    ),
    constraint external_reference_actor_type_valid check (created_by_actor_type = 'STAFF'),
    constraint external_reference_actor_display_not_blank check (
        length(btrim(created_by_actor_display)) between 1 and 100
    ),
    constraint external_reference_identity_unique unique (ticket_id, external_system_id, object_type, external_id)
);

create index external_references_ticket_created_idx
    on external_references (ticket_id, created_at desc, id);
create index external_references_external_identity_idx
    on external_references (external_system_id, object_type, external_id);
