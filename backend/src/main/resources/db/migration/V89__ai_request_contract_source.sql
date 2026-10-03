alter table access_audit_events
    drop constraint access_audit_source_valid;

alter table access_audit_events
    add constraint access_audit_source_valid
        check (source in ('AGENT_UI', 'CUSTOMER_PORTAL', 'PLATFORM_API', 'AI_SERVICE'));

create table ai_requests (
    job_id uuid primary key,
    workspace_key varchar(80) not null,
    requester_staff_id uuid not null references staff_accounts(id),
    ticket_id uuid not null references tickets(id),
    ticket_number bigint not null,
    feature varchar(32) not null,
    status varchar(24) not null,
    expected_ticket_version bigint not null,
    options_json jsonb not null,
    request_fingerprint char(64) not null,
    idempotency_key_fingerprint char(64) not null,
    context_policy_version varchar(32) not null,
    context_revision char(64) not null,
    request_revision bigint not null default 1,
    cancellation_requested boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    deadline_at timestamptz not null,
    constraint ai_request_workspace_key_bounded check (
        length(btrim(workspace_key)) between 1 and 80 and workspace_key !~ '[[:cntrl:]]'
    ),
    constraint ai_request_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft')
    ),
    constraint ai_request_status_valid check (
        status in ('ACCEPTED', 'CANCELLED', 'SUPERSEDED', 'EXPIRED')
    ),
    constraint ai_request_options_object check (jsonb_typeof(options_json) = 'object'),
    constraint ai_request_fingerprints_valid check (
        request_fingerprint ~ '^[0-9a-f]{64}$'
        and idempotency_key_fingerprint ~ '^[0-9a-f]{64}$'
        and context_revision ~ '^[0-9a-f]{64}$'
    ),
    constraint ai_request_revision_positive check (request_revision > 0),
    constraint ai_request_deadline_after_create check (deadline_at > created_at),
    constraint ai_request_cancel_shape check (
        (status = 'CANCELLED' and cancellation_requested)
        or status <> 'CANCELLED'
    ),
    constraint ai_request_idempotency_unique unique (
        workspace_key, requester_staff_id, idempotency_key_fingerprint
    )
);

create index ai_requests_actor_rate_idx
    on ai_requests (workspace_key, requester_staff_id, created_at desc);
create index ai_requests_workspace_rate_idx
    on ai_requests (workspace_key, created_at desc);
create index ai_requests_ticket_recent_idx
    on ai_requests (requester_staff_id, ticket_id, created_at desc, job_id desc);
create index ai_requests_deadline_idx
    on ai_requests (deadline_at, job_id)
    where status = 'ACCEPTED';

create table ai_integration_outbox (
    event_id uuid primary key,
    job_id uuid not null references ai_requests(job_id),
    event_type varchar(32) not null,
    schema_version integer not null,
    request_revision bigint not null,
    payload_json jsonb not null,
    payload_checksum char(64) not null,
    status varchar(20) not null,
    attempts integer not null default 0,
    available_at timestamptz not null,
    lease_owner varchar(100) null,
    lease_expires_at timestamptz null,
    delivered_at timestamptz null,
    created_at timestamptz not null,
    last_error_code varchar(80) null,
    constraint ai_outbox_event_type_valid check (event_type in ('JOB_REQUESTED', 'JOB_CANCELLED', 'JOB_FEEDBACK')),
    constraint ai_outbox_schema_version_positive check (schema_version > 0),
    constraint ai_outbox_request_revision_positive check (request_revision > 0),
    constraint ai_outbox_payload_object check (jsonb_typeof(payload_json) = 'object'),
    constraint ai_outbox_checksum_valid check (payload_checksum ~ '^[0-9a-f]{64}$'),
    constraint ai_outbox_status_valid check (status in ('PENDING', 'LEASED', 'DELIVERED', 'DEAD')),
    constraint ai_outbox_attempts_nonnegative check (attempts >= 0),
    constraint ai_outbox_lease_shape check (
        (status = 'LEASED' and lease_owner is not null and lease_expires_at is not null)
        or (status <> 'LEASED' and lease_owner is null and lease_expires_at is null)
    ),
    constraint ai_outbox_delivery_shape check (
        (status = 'DELIVERED' and delivered_at is not null)
        or (status <> 'DELIVERED' and delivered_at is null)
    ),
    constraint ai_outbox_job_event_revision_unique unique (job_id, event_type, request_revision)
);

create index ai_integration_outbox_dispatch_idx
    on ai_integration_outbox (available_at, created_at, event_id)
    where status = 'PENDING';
create index ai_integration_outbox_lease_idx
    on ai_integration_outbox (lease_expires_at, event_id)
    where status = 'LEASED';

create table ai_context_access_audit_details (
    access_event_id uuid primary key references access_audit_events(id),
    job_id uuid not null references ai_requests(job_id),
    requester_staff_id uuid not null references staff_accounts(id),
    feature varchar(32) not null,
    request_revision bigint not null,
    constraint ai_context_access_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft')
    ),
    constraint ai_context_access_request_revision_positive check (request_revision > 0)
);

create index ai_context_access_job_idx
    on ai_context_access_audit_details (job_id, access_event_id);
create index ai_context_access_requester_idx
    on ai_context_access_audit_details (requester_staff_id, access_event_id);

create trigger ai_context_access_audit_details_immutable
before update or delete on ai_context_access_audit_details
for each row execute function reject_access_audit_mutation();

create table ai_result_access_audit_details (
    access_event_id uuid primary key references access_audit_events(id),
    job_id uuid not null references ai_requests(job_id),
    requester_staff_id uuid not null references staff_accounts(id),
    feature varchar(32) not null,
    request_revision bigint not null,
    constraint ai_result_access_feature_valid check (
        feature in ('ticket.summary', 'ticket.triage', 'ticket.reply_draft')
    ),
    constraint ai_result_access_request_revision_positive check (request_revision > 0)
);

create index ai_result_access_job_idx
    on ai_result_access_audit_details (job_id, access_event_id);

create trigger ai_result_access_audit_details_immutable
before update or delete on ai_result_access_audit_details
for each row execute function reject_access_audit_mutation();

create table ai_request_feedback (
    job_id uuid not null references ai_requests(job_id),
    feedback_type varchar(24) not null,
    requester_staff_id uuid not null references staff_accounts(id),
    source_revision bigint not null,
    reason_code varchar(40) null,
    event_id uuid not null unique,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (job_id, feedback_type),
    constraint ai_request_feedback_type_valid check (
        feedback_type in ('helpful', 'unhelpful', 'inserted', 'edited')
    ),
    constraint ai_request_feedback_revision_positive check (source_revision > 0),
    constraint ai_request_feedback_reason_bounded check (
        reason_code is null or (
            length(btrim(reason_code)) between 1 and 40 and reason_code !~ '[[:cntrl:]]'
        )
    )
);

create table ai_feedback_idempotency (
    requester_staff_id uuid not null references staff_accounts(id),
    idempotency_key_fingerprint char(64) not null,
    request_fingerprint char(64) not null,
    job_id uuid not null references ai_requests(job_id),
    feedback_type varchar(24) not null,
    source_revision bigint not null,
    recorded_at timestamptz not null,
    primary key (requester_staff_id, idempotency_key_fingerprint),
    constraint ai_feedback_idempotency_fingerprints_valid check (
        idempotency_key_fingerprint ~ '^[0-9a-f]{64}$'
        and request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    constraint ai_feedback_idempotency_type_valid check (
        feedback_type in ('helpful', 'unhelpful', 'inserted', 'edited')
    ),
    constraint ai_feedback_idempotency_revision_positive check (source_revision > 0)
);

create table ai_activity_events (
    event_id uuid primary key,
    occurred_at timestamptz not null,
    actor_id uuid not null references staff_accounts(id),
    actor_display_snapshot varchar(100) not null,
    source varchar(32) not null,
    action varchar(32) not null,
    job_id uuid not null references ai_requests(job_id),
    ticket_id uuid not null references tickets(id),
    ticket_number bigint not null,
    request_revision bigint not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    session_fingerprint varchar(100) not null,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint ai_activity_source_valid check (source = 'AGENT_UI'),
    constraint ai_activity_action_valid check (
        action in ('AI_REQUEST_CREATED', 'AI_REQUEST_CANCELLED', 'AI_FEEDBACK_RECORDED')
    ),
    constraint ai_activity_revision_positive check (request_revision > 0),
    constraint ai_activity_session_shape check (
        length(btrim(session_fingerprint)) between 1 and 100 and session_fingerprint !~ '[[:cntrl:]]'
    ),
    constraint ai_activity_metadata_object check (jsonb_typeof(metadata_json) = 'object')
);

create index ai_activity_job_idx on ai_activity_events (job_id, occurred_at, event_id);

create trigger ai_activity_events_immutable
before update or delete on ai_activity_events
for each row execute function reject_access_audit_mutation();

create table ai_knowledge_access_audit_details (
    access_event_id uuid primary key references access_audit_events(id),
    request_ref uuid not null,
    article_id uuid not null,
    revision_id uuid not null,
    purpose varchar(32) not null,
    constraint ai_knowledge_access_purpose_valid check (purpose in ('INDEX', 'RECONCILE', 'RETRIEVAL', 'RESULT'))
);

create index ai_knowledge_access_article_idx
    on ai_knowledge_access_audit_details (article_id, revision_id, access_event_id);

create trigger ai_knowledge_access_audit_details_immutable
before update or delete on ai_knowledge_access_audit_details
for each row execute function reject_access_audit_mutation();
