-- Wave 1 ticket configuration foundation.  All rows are lifecycle-managed;
-- historical definitions/options are deliberately retained rather than deleted.
create table ticket_field_definitions (
    id uuid primary key,
    machine_key varchar(120) not null unique,
    field_type varchar(20) not null,
    staff_label varchar(120) not null,
    staff_description varchar(500) null,
    customer_label varchar(120) null,
    customer_description varchar(500) null,
    customer_visible boolean not null default false,
    customer_editable boolean not null default false,
    agent_visible boolean not null default true,
    agent_editable boolean not null default true,
    searchable boolean not null default false,
    analytics_eligible boolean not null default false,
    sensitive boolean not null default false,
    validation_json jsonb not null default '{}'::jsonb,
    active boolean not null default true,
    definition_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ticket_field_machine_key_valid check (
        machine_key ~ '^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$'
    ),
    constraint ticket_field_type_valid check (
        field_type in ('CHECKBOX', 'SINGLE_SELECT', 'NUMBER', 'SHORT_TEXT', 'LONG_TEXT')
    ),
    constraint ticket_field_staff_label_valid check (
        length(btrim(staff_label)) between 1 and 120 and staff_label !~ '[[:cntrl:]]'
    ),
    constraint ticket_field_customer_editable_visible check (
        not customer_editable or customer_visible
    ),
    constraint ticket_field_agent_editable_visible check (
        not agent_editable or agent_visible
    ),
    constraint ticket_field_descriptions_bounded check (
        (staff_description is null or (length(staff_description) <= 500 and staff_description !~ '[[:cntrl:]]'))
        and (customer_description is null or (length(customer_description) <= 500 and customer_description !~ '[[:cntrl:]]'))
    ),
    constraint ticket_field_validation_object check (jsonb_typeof(validation_json) = 'object')
);

create index ticket_field_definitions_active_idx
    on ticket_field_definitions (active, machine_key);

create table ticket_field_options (
    id uuid primary key,
    field_definition_id uuid not null references ticket_field_definitions(id),
    machine_key varchar(80) not null,
    staff_label varchar(120) not null,
    customer_label varchar(120) null,
    display_order integer not null,
    active boolean not null default true,
    option_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (field_definition_id, machine_key),
    unique (field_definition_id, display_order),
    constraint ticket_field_option_machine_key_valid check (
        machine_key ~ '^[a-z][a-z0-9-]*$'
    ),
    constraint ticket_field_option_label_valid check (
        length(btrim(staff_label)) between 1 and 120 and staff_label !~ '[[:cntrl:]]'
    ),
    constraint ticket_field_option_customer_label_valid check (
        customer_label is null or (length(customer_label) <= 120 and customer_label !~ '[[:cntrl:]]')
    ),
    constraint ticket_field_option_order_valid check (display_order >= 0)
);

create index ticket_field_options_order_idx
    on ticket_field_options (field_definition_id, display_order, id);

-- Form drafts may be edited; each publish writes an immutable snapshot row.
create table ticket_forms (
    id uuid primary key,
    name varchar(120) not null,
    description varchar(500) null,
    lifecycle varchar(16) not null,
    default_for_customer boolean not null default false,
    default_for_agent boolean not null default false,
    draft_definition_json jsonb not null,
    current_version integer not null default 1,
    published_version integer null,
    aggregate_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ticket_forms_lifecycle_valid check (lifecycle in ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    constraint ticket_forms_name_valid check (length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'),
    constraint ticket_forms_description_valid check (
        description is null or (length(description) <= 500 and description !~ '[[:cntrl:]]')
    ),
    constraint ticket_forms_definition_object check (jsonb_typeof(draft_definition_json) = 'object'),
    constraint ticket_forms_published_shape check (
        (lifecycle = 'DRAFT' and published_version is null)
        or (lifecycle in ('PUBLISHED', 'ARCHIVED') and published_version is not null)
    )
);

create table ticket_form_versions (
    form_id uuid not null references ticket_forms(id),
    version integer not null,
    definition_json jsonb not null,
    published_by_staff_id uuid not null references staff_accounts(id),
    published_by_display varchar(100) not null,
    published_at timestamptz not null,
    primary key (form_id, version),
    constraint ticket_form_versions_definition_object check (jsonb_typeof(definition_json) = 'object')
);

alter table ticket_forms
    add constraint ticket_forms_published_version_fk
    foreign key (id, published_version)
    references ticket_form_versions(form_id, version)
    deferrable initially deferred;

create table ticket_tag_definitions (
    id uuid primary key,
    normalized_value varchar(80) not null unique,
    label varchar(120) not null,
    active boolean not null default true,
    definition_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ticket_tag_normalized_value_valid check (
        normalized_value ~ '^[a-z0-9](?:[a-z0-9_-]{0,78}[a-z0-9])?$'
    ),
    constraint ticket_tag_label_valid check (
        length(btrim(label)) between 1 and 120 and label !~ '[[:cntrl:]]'
    )
);

create table custom_ticket_statuses (
    id uuid primary key,
    machine_key varchar(80) not null unique,
    agent_label varchar(120) not null,
    customer_label varchar(120) null,
    status_category varchar(20) not null,
    active boolean not null default true,
    display_order integer not null,
    default_for_category boolean not null default false,
    allowed_form_ids uuid[] not null default '{}',
    description varchar(500) null,
    definition_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint custom_ticket_status_machine_key_valid check (machine_key ~ '^[a-z][a-z0-9-]*$'),
    constraint custom_ticket_status_category_valid check (
        status_category in ('NEW', 'OPEN', 'PENDING', 'ON_HOLD', 'SOLVED', 'CLOSED')
    ),
    constraint custom_ticket_status_closed_prohibited check (status_category <> 'CLOSED'),
    constraint custom_ticket_status_order_valid check (display_order >= 0),
    constraint custom_ticket_status_agent_label_valid check (
        length(btrim(agent_label)) between 1 and 120 and agent_label !~ '[[:cntrl:]]'
    )
);

create unique index custom_ticket_statuses_default_category_idx
    on custom_ticket_statuses (status_category)
    where default_for_category and active;

-- Values retain the original typed shape and stable option identity.  Runtime code
-- enforces the field-type relation and active/form projection before it mutates rows.
create table ticket_custom_field_values (
    ticket_id uuid not null references tickets(id),
    field_definition_id uuid not null references ticket_field_definitions(id),
    boolean_value boolean null,
    number_value numeric(30, 12) null,
    option_id uuid null references ticket_field_options(id),
    short_text_value varchar(1000) null,
    long_text_value varchar(10000) null,
    field_definition_version bigint not null,
    form_id uuid null references ticket_forms(id),
    form_version integer null,
    updated_at timestamptz not null,
    primary key (ticket_id, field_definition_id),
    constraint ticket_custom_field_value_one_typed_value check (
        num_nonnulls(boolean_value, number_value, option_id, short_text_value, long_text_value) = 1
    ),
    constraint ticket_custom_field_value_form_snapshot_shape check (
        (form_id is null and form_version is null) or (form_id is not null and form_version is not null)
    )
);

create index ticket_custom_field_values_option_idx
    on ticket_custom_field_values (field_definition_id, option_id)
    where option_id is not null;
create index ticket_custom_field_values_number_idx
    on ticket_custom_field_values (field_definition_id, number_value)
    where number_value is not null;

create table ticket_tag_assignments (
    ticket_id uuid not null references tickets(id),
    tag_definition_id uuid not null references ticket_tag_definitions(id),
    assigned_at timestamptz not null,
    primary key (ticket_id, tag_definition_id)
);

alter table tickets add column custom_status_id uuid null references custom_ticket_statuses(id);
create index tickets_custom_status_idx on tickets (custom_status_id) where custom_status_id is not null;
