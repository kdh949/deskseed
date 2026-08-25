-- Macro definitions are mutable aggregate roots. Each saved definition produces
-- immutable version and ordered-action rows; activation is an explicit history.
create table macro_definitions (
    id uuid primary key,
    normalized_name varchar(120) not null,
    name varchar(120) not null,
    scope varchar(16) not null,
    owner_staff_id uuid null references staff_accounts(id),
    current_version integer not null,
    active_version integer null,
    aggregate_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint macro_definitions_scope_valid check (scope in ('PERSONAL', 'SHARED')),
    constraint macro_definitions_owner_shape check (
        (scope = 'PERSONAL' and owner_staff_id is not null)
        or (scope = 'SHARED' and owner_staff_id is null)
    ),
    constraint macro_definitions_name_valid check (
        length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'
    ),
    constraint macro_definitions_normalized_name_valid check (
        length(normalized_name) between 1 and 120 and normalized_name = lower(btrim(name))
    ),
    constraint macro_definitions_versions_valid check (
        current_version >= 1 and (active_version is null or active_version between 1 and current_version)
    ),
    constraint macro_definitions_aggregate_version_valid check (aggregate_version >= 1)
);

create unique index macro_definitions_personal_name_idx
    on macro_definitions (owner_staff_id, normalized_name)
    where scope = 'PERSONAL';

create unique index macro_definitions_shared_name_idx
    on macro_definitions (normalized_name)
    where scope = 'SHARED';

create table macro_versions (
    macro_id uuid not null references macro_definitions(id),
    version integer not null,
    name varchar(120) not null,
    created_by_staff_id uuid not null references staff_accounts(id),
    created_by_display varchar(100) not null,
    created_at timestamptz not null,
    primary key (macro_id, version),
    constraint macro_versions_version_valid check (version >= 1),
    constraint macro_versions_name_valid check (
        length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'
    ),
    constraint macro_versions_actor_display_valid check (
        length(btrim(created_by_display)) between 1 and 100 and created_by_display !~ '[[:cntrl:]]'
    )
);

alter table macro_definitions
    add constraint macro_definitions_active_version_fk
    foreign key (id, active_version)
    references macro_versions(macro_id, version)
    deferrable initially deferred;

create table macro_actions (
    macro_id uuid not null,
    macro_version integer not null,
    ordinal integer not null,
    action_type varchar(32) not null,
    configuration_json jsonb not null,
    primary key (macro_id, macro_version, ordinal),
    foreign key (macro_id, macro_version) references macro_versions(macro_id, version),
    constraint macro_actions_ordinal_valid check (ordinal between 0 and 49),
    constraint macro_actions_type_valid check (
        action_type in (
            'STATUS', 'PRIORITY', 'GROUP', 'ASSIGNEE', 'ADD_TAG', 'REMOVE_TAG',
            'CUSTOM_FIELD', 'CUSTOM_STATUS', 'COMMENT'
        )
    ),
    constraint macro_actions_configuration_object check (jsonb_typeof(configuration_json) = 'object')
);

create table macro_activations (
    id uuid primary key,
    macro_id uuid not null,
    macro_version integer not null,
    activation_state varchar(16) not null,
    actor_staff_id uuid not null references staff_accounts(id),
    actor_display varchar(100) not null,
    source varchar(30) not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    occurred_at timestamptz not null,
    foreign key (macro_id, macro_version) references macro_versions(macro_id, version),
    constraint macro_activations_state_valid check (activation_state in ('ACTIVE', 'INACTIVE')),
    constraint macro_activations_source_valid check (source in ('AGENT_UI', 'ADMIN_UI')),
    constraint macro_activations_actor_display_valid check (
        length(btrim(actor_display)) between 1 and 100 and actor_display !~ '[[:cntrl:]]'
    ),
    constraint macro_activations_request_context_valid check (
        length(request_id) between 1 and 100 and request_id !~ '[[:cntrl:]]'
        and length(correlation_id) between 1 and 100 and correlation_id !~ '[[:cntrl:]]'
    )
);

create index macro_activations_history_idx
    on macro_activations (macro_id, occurred_at desc, id);

create or replace function reject_macro_history_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'macro history rows are immutable';
end;
$$;

create trigger macro_versions_immutable
before update or delete on macro_versions
for each row execute function reject_macro_history_mutation();

create trigger macro_actions_immutable
before update or delete on macro_actions
for each row execute function reject_macro_history_mutation();

create trigger macro_activations_immutable
before update or delete on macro_activations
for each row execute function reject_macro_history_mutation();
