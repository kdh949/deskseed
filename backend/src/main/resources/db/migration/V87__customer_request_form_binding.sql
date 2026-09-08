-- Freeze customer field semantics with each published form version. Existing
-- versions are initialized from the upgrade-time catalog; this is not a claim
-- that historical labels or pre-upgrade validation rules can be reconstructed.
alter table ticket_form_versions add column customer_field_snapshot_json jsonb not null default '[]'::jsonb
    check (jsonb_typeof(customer_field_snapshot_json) = 'array');

drop trigger ticket_form_versions_immutable on ticket_form_versions;
update ticket_form_versions version set customer_field_snapshot_json = (
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', field.id, 'machineKey', field.machine_key, 'type', field.field_type,
        'definitionVersion', field.definition_version, 'customerEditable', field.customer_editable,
        'validation', field.validation_json,
        'options', (select coalesce(jsonb_agg(jsonb_build_object(
            'id', option.id, 'machineKey', option.machine_key, 'order', option.display_order
        ) order by option.display_order, option.id), '[]'::jsonb)
        from ticket_field_options option where option.field_definition_id = field.id
          and option.active and option.customer_label is not null)
    ) order by (placement->>'order')::integer), '[]'::jsonb)
    from jsonb_array_elements(version.definition_json->'placements') placement
    join ticket_field_definitions field on field.id = (placement->>'fieldId')::uuid
    where field.active and field.customer_visible and field.customer_label is not null
);
create trigger ticket_form_versions_immutable before update or delete on ticket_form_versions
    for each row execute function reject_ticket_form_version_mutation();

create table ticket_customer_form_bindings (
    ticket_id uuid primary key references tickets(id),
    form_id uuid not null,
    form_version integer not null,
    bound_at timestamptz not null,
    foreign key (form_id, form_version) references ticket_form_versions(form_id, version)
);
create index ticket_customer_form_bindings_form_idx on ticket_customer_form_bindings(form_id, ticket_id);

-- Operational replay receipt, deliberately separate from immutable ticket audit.
create table customer_request_command_receipts (
    command_digest varchar(64) primary key check (command_digest ~ '^[0-9a-f]{64}$'),
    payload_digest varchar(64) not null check (payload_digest ~ '^[0-9a-f]{64}$'),
    ticket_id uuid not null references tickets(id),
    ticket_number bigint not null,
    created_at timestamptz not null,
    expires_at timestamptz not null check (expires_at > created_at)
);
create index customer_request_command_receipts_expiry_idx on customer_request_command_receipts(expires_at, command_digest);
