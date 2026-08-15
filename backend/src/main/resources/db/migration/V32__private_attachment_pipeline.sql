create table attachment_objects (
    id uuid primary key,
    storage_key varchar(120) not null unique,
    uploaded_actor_type varchar(30) not null,
    uploaded_actor_id uuid not null,
    bound_ticket_id uuid null,
    allowed_visibility varchar(16) null,
    initial_public_submission boolean not null default false,
    file_name varchar(255) not null,
    declared_content_type varchar(127) null,
    detected_content_type varchar(127) null,
    content_type varchar(127) null,
    size_bytes bigint not null default 0,
    sha256 varchar(64) null,
    scan_status varchar(20) not null,
    scan_failure_code varchar(80) null,
    created_at timestamptz not null,
    scanned_at timestamptz null,
    linked_at timestamptz null,
    expires_at timestamptz not null,
    deleted_at timestamptz null,
    constraint attachment_objects_storage_key_shape check (storage_key ~ '^attachments/quarantine/[0-9a-f-]{36}$'),
    constraint attachment_objects_actor_type check (uploaded_actor_type in ('STAFF', 'CUSTOMER')),
    constraint attachment_objects_visibility check (allowed_visibility is null or allowed_visibility in ('PUBLIC', 'INTERNAL')),
    constraint attachment_objects_file_name_bounded check (
        length(btrim(file_name)) between 1 and 255 and file_name !~ '[[:cntrl:]]' and position('/' in file_name) = 0 and position('\\' in file_name) = 0
    ),
    constraint attachment_objects_content_type_bounded check (
        (declared_content_type is null or (length(declared_content_type) between 1 and 127 and declared_content_type !~ '[[:cntrl:]]'))
        and (detected_content_type is null or (length(detected_content_type) between 1 and 127 and detected_content_type !~ '[[:cntrl:]]'))
        and (content_type is null or (length(content_type) between 1 and 127 and content_type !~ '[[:cntrl:]]'))
    ),
    constraint attachment_objects_size_nonnegative check (size_bytes >= 0),
    constraint attachment_objects_checksum_shape check (sha256 is null or sha256 ~ '^[0-9a-f]{64}$'),
    constraint attachment_objects_status check (scan_status in ('QUARANTINED', 'CLEAN', 'INFECTED', 'FAILED', 'DELETED', 'EXPIRED')),
    constraint attachment_objects_expiry_valid check (expires_at > created_at),
    constraint attachment_objects_clean_shape check (
        (scan_status <> 'CLEAN') or (size_bytes > 0 and sha256 is not null and content_type is not null and scanned_at is not null)
    ),
    constraint attachment_objects_customer_binding check (
        uploaded_actor_type <> 'CUSTOMER' or (
            allowed_visibility = 'PUBLIC' and (bound_ticket_id is not null or initial_public_submission)
        )
    ),
    constraint attachment_objects_initial_submission_shape check (
        not initial_public_submission or (uploaded_actor_type = 'CUSTOMER' and allowed_visibility = 'PUBLIC' and bound_ticket_id is null)
    ),
    constraint attachment_objects_bound_ticket_fk foreign key (bound_ticket_id) references tickets(id)
);

create index attachment_objects_cleanup_idx
    on attachment_objects (expires_at, id)
    where scan_status in ('QUARANTINED', 'CLEAN', 'INFECTED', 'FAILED');

create index attachment_objects_owner_unlinked_idx
    on attachment_objects (uploaded_actor_type, uploaded_actor_id, created_at)
    where scan_status = 'CLEAN' and linked_at is null;

create table ticket_comment_attachments (
    attachment_id uuid primary key references attachment_objects(id),
    ticket_id uuid not null references tickets(id),
    comment_id uuid not null references ticket_comments(id),
    visibility varchar(16) not null,
    linked_at timestamptz not null,
    constraint ticket_comment_attachments_visibility check (visibility in ('PUBLIC', 'INTERNAL'))
);

create index ticket_comment_attachments_comment_idx
    on ticket_comment_attachments (comment_id, visibility, attachment_id);

create index ticket_comment_attachments_ticket_idx
    on ticket_comment_attachments (ticket_id, attachment_id);

alter table access_audit_events
    drop constraint access_audit_actor_type_valid,
    drop constraint access_audit_source_valid,
    drop constraint access_audit_action_valid,
    drop constraint access_audit_search_shape_valid;

alter table access_audit_events
    add constraint access_audit_actor_type_valid check (actor_type in ('STAFF', 'CUSTOMER', 'INTEGRATION_CLIENT')),
    add constraint access_audit_source_valid check (source in ('AGENT_UI', 'CUSTOMER_PORTAL', 'PLATFORM_API')),
    add constraint access_audit_action_valid check (
        action in (
            'TICKET_VIEWED', 'SEARCH_EXECUTED', 'SEARCH_RESULT_OPENED', 'API_RESOURCE_READ',
            'CUSTOMER_SEARCH_EXECUTED', 'VIEW_EXECUTED', 'ATTACHMENT_DOWNLOADED'
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
        or action in ('TICKET_VIEWED', 'API_RESOURCE_READ')
    );
