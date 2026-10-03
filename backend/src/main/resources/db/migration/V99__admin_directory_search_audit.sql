alter table access_audit_events
    drop constraint access_audit_source_valid,
    drop constraint access_audit_action_valid,
    drop constraint access_audit_search_shape_valid;

alter table access_audit_events
    add constraint access_audit_source_valid check (
        source in ('AGENT_UI', 'CUSTOMER_PORTAL', 'PLATFORM_API', 'AI_SERVICE', 'ADMIN_UI')
    ),
    add constraint access_audit_action_valid check (
        action in (
            'TICKET_VIEWED', 'SEARCH_EXECUTED', 'SEARCH_RESULT_OPENED', 'API_RESOURCE_READ',
            'CUSTOMER_SEARCH_EXECUTED', 'VIEW_EXECUTED', 'ATTACHMENT_DOWNLOADED', 'MACRO_PREVIEWED',
            'ADMIN_STAFF_SEARCH_EXECUTED', 'ADMIN_GROUP_SEARCH_EXECUTED'
        )
    ),
    add constraint access_audit_admin_search_source_valid check (
        (action in ('ADMIN_STAFF_SEARCH_EXECUTED', 'ADMIN_GROUP_SEARCH_EXECUTED')
            and source = 'ADMIN_UI' and actor_type = 'STAFF' and auth_type is not null and auth_type = 'STAFF_SESSION'
            and session_fingerprint is not null and interaction_id is not null
            and outcome = 'SUCCEEDED' and http_status = 200)
        or
        (action not in ('ADMIN_STAFF_SEARCH_EXECUTED', 'ADMIN_GROUP_SEARCH_EXECUTED') and source <> 'ADMIN_UI')
    ),
    add constraint access_audit_search_shape_valid check (
        (action in ('SEARCH_EXECUTED', 'CUSTOMER_SEARCH_EXECUTED', 'ADMIN_STAFF_SEARCH_EXECUTED', 'ADMIN_GROUP_SEARCH_EXECUTED')
            and resource_type = 'SEARCH' and resource_id is null
            and ticket_number is null and origin_search_event_id is null)
        or
        (action = 'SEARCH_RESULT_OPENED' and resource_type = 'TICKET'
            and resource_id is not null and ticket_number is not null and origin_search_event_id is not null)
        or
        (action = 'VIEW_EXECUTED' and resource_type = 'SAVED_VIEW'
            and resource_id is not null and ticket_number is null and origin_search_event_id is null)
        or
        (action = 'ATTACHMENT_DOWNLOADED' and resource_type = 'ATTACHMENT'
            and resource_id is not null and ticket_number is not null and origin_search_event_id is null)
        or
        (action = 'MACRO_PREVIEWED' and resource_type = 'MACRO'
            and resource_id is not null and ticket_number is not null and origin_search_event_id is null)
        or action in ('TICKET_VIEWED', 'API_RESOURCE_READ')
    );

-- Existing protected search details, ciphertext expiry and append-only triggers are reused.
-- ADMIN searches deliberately create no ticket/customer result-membership rows.
