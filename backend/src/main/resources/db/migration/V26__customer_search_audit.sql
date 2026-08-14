alter table access_audit_events
    drop constraint access_audit_action_valid,
    drop constraint access_audit_search_shape_valid;

alter table access_audit_events
    add constraint access_audit_action_valid check (
        action in (
            'TICKET_VIEWED', 'SEARCH_EXECUTED', 'SEARCH_RESULT_OPENED', 'API_RESOURCE_READ',
            'CUSTOMER_SEARCH_EXECUTED'
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
        action in ('TICKET_VIEWED', 'API_RESOURCE_READ')
    );

create table search_audit_customer_result_items (
    access_event_id uuid not null references access_audit_events (id),
    customer_id uuid not null,
    result_ordinal integer not null,
    primary key (access_event_id, customer_id),
    constraint search_audit_customer_result_ordinal_unique unique (access_event_id, result_ordinal),
    constraint search_audit_customer_result_ordinal_nonnegative check (result_ordinal >= 0)
);

create index search_audit_customer_result_customer_idx
    on search_audit_customer_result_items (customer_id, access_event_id);

create trigger search_audit_customer_result_items_immutable
before update or delete on search_audit_customer_result_items
for each row execute function reject_access_audit_mutation();
