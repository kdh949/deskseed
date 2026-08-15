create table saved_ticket_views (
    id uuid primary key,
    view_key varchar(100) not null,
    scope varchar(16) not null,
    -- This is an ownership/audit identity, not a lifecycle-owned child row.  A foreign
    -- key would make a staff-account TRUNCATE/retention operation cascade into the
    -- immutable SYSTEM seed definitions and unrelated saved-view configuration.
    owner_staff_id uuid null,
    name varchar(120) not null,
    category varchar(80) not null,
    conditions_json jsonb not null,
    columns_json jsonb not null,
    sort varchar(80) not null,
    sort_position integer not null,
    active boolean not null default true,
    definition_version bigint not null default 1,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint saved_ticket_views_key_unique unique (view_key),
    constraint saved_ticket_views_scope_valid check (scope in ('SYSTEM', 'PERSONAL', 'SHARED')),
    constraint saved_ticket_views_owner_shape check (
        (scope = 'PERSONAL' and owner_staff_id is not null)
        or (scope in ('SYSTEM', 'SHARED') and owner_staff_id is null)
    ),
    constraint saved_ticket_views_name_bounded check (
        length(btrim(name)) between 1 and 120 and name !~ '[[:cntrl:]]'
    ),
    constraint saved_ticket_views_category_bounded check (
        length(btrim(category)) between 1 and 80 and category !~ '[[:cntrl:]]'
    ),
    constraint saved_ticket_views_conditions_object check (jsonb_typeof(conditions_json) = 'object'),
    constraint saved_ticket_views_columns_array check (
        jsonb_typeof(columns_json) = 'array' and jsonb_array_length(columns_json) between 1 and 12
    ),
    constraint saved_ticket_views_sort_valid check (sort = 'updatedAt:desc,ticketNumber:desc'),
    constraint saved_ticket_views_position_nonnegative check (sort_position >= 0),
    constraint saved_ticket_views_definition_version_positive check (definition_version >= 1)
);

create unique index saved_ticket_views_personal_name_unique
    on saved_ticket_views (owner_staff_id, lower(name))
    where scope = 'PERSONAL' and active;

create unique index saved_ticket_views_shared_name_unique
    on saved_ticket_views (lower(name))
    where scope = 'SHARED' and active;

create index saved_ticket_views_visible_order_idx
    on saved_ticket_views (scope, owner_staff_id, sort_position, view_key)
    where active;

create table saved_view_order_states (
    scope varchar(16) not null,
    owner_scope_key uuid not null,
    order_version bigint not null,
    updated_at timestamptz not null,
    primary key (scope, owner_scope_key),
    constraint saved_view_order_states_scope_valid check (scope in ('PERSONAL', 'SHARED')),
    constraint saved_view_order_states_version_positive check (order_version >= 1)
);

insert into saved_view_order_states (scope, owner_scope_key, order_version, updated_at)
values ('SHARED', '00000000-0000-0000-0000-000000000000'::uuid, 1, clock_timestamp());

insert into saved_ticket_views (
    id, view_key, scope, owner_staff_id, name, category, conditions_json, columns_json,
    sort, sort_position, active, definition_version, created_at, updated_at
) values
(
    '90000000-0000-4000-8000-000000000001'::uuid,
    'my-open', 'SYSTEM', null, '내 open', '내 작업',
    '{"version":1,"all":[{"field":"STATUS","operator":"EQUALS","values":["OPEN"]},{"field":"ASSIGNEE","operator":"IS_CURRENT_ACTOR","values":[]}],"any":[]}'::jsonb,
    '["TICKET_NUMBER","SUBJECT","STATUS","PRIORITY","UPDATED_AT"]'::jsonb,
    'updatedAt:desc,ticketNumber:desc', 1, true, 1, clock_timestamp(), clock_timestamp()
),
(
    '90000000-0000-4000-8000-000000000002'::uuid,
    'unassigned-my-groups', 'SYSTEM', null, '내 그룹 미배정', '내 작업',
    '{"version":1,"all":[{"field":"STATUS","operator":"LESS_THAN_SOLVED","values":[]},{"field":"ASSIGNEE","operator":"IS_UNASSIGNED","values":[]},{"field":"GROUP","operator":"IS_CURRENT_ACTOR_GROUP","values":[]}],"any":[]}'::jsonb,
    '["TICKET_NUMBER","SUBJECT","STATUS","GROUP","UPDATED_AT"]'::jsonb,
    'updatedAt:desc,ticketNumber:desc', 2, true, 1, clock_timestamp(), clock_timestamp()
),
(
    '90000000-0000-4000-8000-000000000003'::uuid,
    'pending', 'SYSTEM', null, 'Pending', '공유',
    '{"version":1,"all":[{"field":"STATUS","operator":"EQUALS","values":["PENDING"]}],"any":[]}'::jsonb,
    '["TICKET_NUMBER","SUBJECT","STATUS","PRIORITY","UPDATED_AT"]'::jsonb,
    'updatedAt:desc,ticketNumber:desc', 3, true, 1, clock_timestamp(), clock_timestamp()
),
(
    '90000000-0000-4000-8000-000000000004'::uuid,
    'recently-solved', 'SYSTEM', null, '최근 solved', '최근',
    '{"version":1,"all":[{"field":"STATUS","operator":"EQUALS","values":["SOLVED"]},{"field":"ASSIGNEE","operator":"IS_CURRENT_ACTOR","values":[]},{"field":"UPDATED_AT","operator":"WITHIN_LAST_DAYS","values":["30"]}],"any":[]}'::jsonb,
    '["TICKET_NUMBER","SUBJECT","STATUS","ASSIGNEE","UPDATED_AT"]'::jsonb,
    'updatedAt:desc,ticketNumber:desc', 4, true, 1, clock_timestamp(), clock_timestamp()
),
(
    '90000000-0000-4000-8000-000000000005'::uuid,
    'my-child-tasks', 'SYSTEM', null, '내 child tasks', '내 작업',
    '{"version":1,"all":[{"field":"TICKET_KIND","operator":"EQUALS","values":["INTERNAL_CHILD"]},{"field":"STATUS","operator":"LESS_THAN_SOLVED","values":[]},{"field":"ASSIGNEE","operator":"IS_CURRENT_ACTOR","values":[]}],"any":[]}'::jsonb,
    '["TICKET_NUMBER","SUBJECT","STATUS","PRIORITY","UPDATED_AT"]'::jsonb,
    'updatedAt:desc,ticketNumber:desc', 5, true, 1, clock_timestamp(), clock_timestamp()
);

alter table access_audit_events
    drop constraint access_audit_action_valid,
    drop constraint access_audit_search_shape_valid;

alter table access_audit_events
    add constraint access_audit_action_valid check (
        action in (
            'TICKET_VIEWED', 'SEARCH_EXECUTED', 'SEARCH_RESULT_OPENED', 'API_RESOURCE_READ',
            'CUSTOMER_SEARCH_EXECUTED', 'VIEW_EXECUTED'
        )
    ),
    add constraint access_audit_search_shape_valid check (
        (action = 'SEARCH_EXECUTED'
            and resource_type = 'SEARCH'
            and resource_id is null
            and ticket_number is null
            and origin_search_event_id is null)
        or
        (action = 'CUSTOMER_SEARCH_EXECUTED'
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
        (action = 'VIEW_EXECUTED'
            and resource_type = 'SAVED_VIEW'
            and resource_id is not null
            and ticket_number is null
            and origin_search_event_id is null)
        or action in ('TICKET_VIEWED', 'API_RESOURCE_READ')
    );
