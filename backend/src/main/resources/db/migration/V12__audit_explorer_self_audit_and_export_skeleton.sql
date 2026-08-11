create unique index admin_security_audit_semantic_view_unique
    on admin_security_audit_events (
        actor_id,
        event_type,
        target_type,
        coalesce(target_id, '00000000-0000-0000-0000-000000000000'::uuid),
        (metadata_json::jsonb ->> 'interactionId')
    )
    where actor_id is not null
      and event_type = 'AUDIT_LOG_VIEWED'
      and metadata_json::jsonb ? 'interactionId';

create table audit_export_jobs (
    id uuid primary key,
    requester_id uuid not null references staff_accounts(id),
    status varchar(20) not null,
    format varchar(10) not null,
    filters_json jsonb not null,
    fields_json jsonb not null,
    reason varchar(1000) not null,
    permission_snapshot_json jsonb not null,
    request_id varchar(100) not null,
    correlation_id varchar(100) not null,
    interaction_id uuid not null,
    created_at timestamptz not null,
    constraint audit_export_status_valid check (status = 'REQUESTED'),
    constraint audit_export_format_valid check (format in ('CSV', 'JSONL')),
    constraint audit_export_filters_object_valid check (jsonb_typeof(filters_json) = 'object'),
    constraint audit_export_fields_array_valid check (
        jsonb_typeof(fields_json) = 'array' and jsonb_array_length(fields_json) between 1 and 20
    ),
    constraint audit_export_reason_bounded check (
        length(btrim(reason)) between 1 and 1000 and reason !~ '[[:cntrl:]]'
    ),
    constraint audit_export_permission_snapshot_array_valid check (
        jsonb_typeof(permission_snapshot_json) = 'array'
    )
);

create index audit_export_jobs_requester_cursor_idx
    on audit_export_jobs (requester_id, created_at desc, id desc);

create table audit_export_artifacts (
    job_id uuid primary key references audit_export_jobs(id),
    state varchar(20) not null,
    generation_available boolean not null,
    created_at timestamptz not null,
    constraint audit_export_artifact_state_valid check (state = 'NOT_CREATED'),
    constraint audit_export_artifact_generation_unavailable check (generation_available = false)
);

