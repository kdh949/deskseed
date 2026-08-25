-- Macro preview is an explicit sensitive read. It is separate from semantic
-- TICKET_VIEWED navigation and records the exact macro/ticket versions used.
alter table access_audit_events
    drop constraint access_audit_action_valid,
    drop constraint access_audit_search_shape_valid;

alter table access_audit_events
    add constraint access_audit_action_valid check (
        action in (
            'TICKET_VIEWED', 'SEARCH_EXECUTED', 'SEARCH_RESULT_OPENED', 'API_RESOURCE_READ',
            'CUSTOMER_SEARCH_EXECUTED', 'VIEW_EXECUTED', 'ATTACHMENT_DOWNLOADED', 'MACRO_PREVIEWED'
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
        or
        (action = 'ATTACHMENT_DOWNLOADED'
            and resource_type = 'ATTACHMENT'
            and resource_id is not null
            and ticket_number is not null
            and origin_search_event_id is null)
        or
        (action = 'MACRO_PREVIEWED'
            and resource_type = 'MACRO'
            and resource_id is not null
            and ticket_number is not null
            and origin_search_event_id is null)
        or action in ('TICKET_VIEWED', 'API_RESOURCE_READ')
    );

create table macro_preview_audit_details (
    access_event_id uuid primary key references access_audit_events(id),
    macro_id uuid not null,
    macro_version integer not null,
    ticket_id uuid not null references tickets(id),
    ticket_version bigint not null,
    foreign key (macro_id, macro_version) references macro_versions(macro_id, version),
    constraint macro_preview_version_valid check (macro_version >= 1 and ticket_version >= 0)
);

create index macro_preview_audit_ticket_idx
    on macro_preview_audit_details (ticket_id, access_event_id);

create trigger macro_preview_audit_details_immutable
before update or delete on macro_preview_audit_details
for each row execute function reject_access_audit_mutation();
