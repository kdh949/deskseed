\set ON_ERROR_STOP on
\timing on

create table audit_activity_projection (
    id uuid primary key,
    ledger_type varchar(30) not null,
    source_event_id uuid not null,
    source_parent_id uuid null,
    occurred_at timestamptz not null,
    actor_type varchar(30) not null,
    actor_id uuid null,
    actor_display_snapshot varchar(100) not null,
    source varchar(40) not null,
    action varchar(80) not null,
    outcome varchar(20) not null,
    resource_type varchar(40) null,
    resource_id uuid null,
    ticket_id uuid null,
    ticket_number bigint null,
    group_id uuid null,
    field_name varchar(60) null,
    old_value_json jsonb null,
    new_value_json jsonb null,
    metadata_json jsonb not null,
    request_id varchar(100) null,
    correlation_id varchar(100) null,
    interaction_id uuid null,
    session_fingerprint varchar(100) null,
    auth_type varchar(40) null,
    ip_address varchar(64) null,
    user_agent varchar(256) null,
    origin_search_event_id uuid null,
    query_redacted varchar(500) null,
    search_fingerprint varchar(100) null,
    search_filters_json jsonb null,
    search_sort varchar(100) null,
    search_result_count bigint null,
    protected_content_available boolean not null default false,
    projected_at timestamptz not null,
    constraint audit_activity_projection_source_unique unique (ledger_type, source_event_id)
);

create index audit_activity_projection_cursor_idx
    on audit_activity_projection (occurred_at desc, id desc);
create index audit_activity_projection_ledger_cursor_idx
    on audit_activity_projection (ledger_type, occurred_at desc, id desc);
create index audit_activity_projection_actor_cursor_idx
    on audit_activity_projection (actor_id, occurred_at desc, id desc);
create index audit_activity_projection_ticket_cursor_idx
    on audit_activity_projection (ticket_number, occurred_at desc, id desc)
    where ticket_number is not null;
create index audit_activity_projection_action_cursor_idx
    on audit_activity_projection (action, occurred_at desc, id desc);
create index audit_activity_projection_group_cursor_idx
    on audit_activity_projection (group_id, occurred_at desc, id desc)
    where group_id is not null;
create index audit_activity_projection_field_cursor_idx
    on audit_activity_projection (field_name, occurred_at desc, id desc)
    where field_name is not null;
create index audit_activity_projection_source_cursor_idx
    on audit_activity_projection (source, occurred_at desc, id desc);
create index audit_activity_projection_outcome_cursor_idx
    on audit_activity_projection (outcome, occurred_at desc, id desc);
create index audit_activity_projection_request_idx
    on audit_activity_projection (request_id)
    where request_id is not null;
create index audit_activity_projection_correlation_idx
    on audit_activity_projection (correlation_id)
    where correlation_id is not null;
create index audit_activity_projection_search_fingerprint_cursor_idx
    on audit_activity_projection (search_fingerprint, occurred_at desc, id desc)
    where search_fingerprint is not null;
create index audit_activity_projection_origin_search_idx
    on audit_activity_projection (origin_search_event_id, occurred_at, id)
    where origin_search_event_id is not null;

insert into audit_activity_projection (
    id, ledger_type, source_event_id, source_parent_id, occurred_at,
    actor_type, actor_id, actor_display_snapshot, source, action, outcome,
    resource_type, resource_id, ticket_id, ticket_number, group_id, field_name,
    old_value_json, new_value_json, metadata_json, request_id, correlation_id,
    interaction_id, session_fingerprint, auth_type, ip_address, user_agent,
    origin_search_event_id, query_redacted, search_fingerprint,
    search_filters_json, search_sort, search_result_count,
    protected_content_available, projected_at
)
select
    md5('projection-' || sequence)::uuid,
    case sequence % 3
        when 0 then 'TICKET_CHANGE'
        when 1 then 'ACCESS_SEARCH'
        else 'ADMIN_SECURITY'
    end,
    md5('source-' || sequence)::uuid,
    null,
    '2026-08-12 00:00:00+00'::timestamptz
        - ((sequence % 2592000) * interval '1 second'),
    'STAFF',
    md5('actor-' || (sequence % 1000))::uuid,
    'Synthetic actor ' || (sequence % 1000),
    case sequence % 3 when 0 then 'AGENT_UI' when 1 then 'ADMIN_UI' else 'SYSTEM_JOB' end,
    case sequence % 10
        when 0 then 'SEARCH_EXECUTED'
        when 1 then 'SEARCH_RESULT_OPENED'
        when 2 then 'TICKET_VIEWED'
        when 3 then 'STATUS_CHANGED'
        when 4 then 'PRIORITY_CHANGED'
        when 5 then 'GROUP_CHANGED'
        when 6 then 'ASSIGNEE_CHANGED'
        when 7 then 'STAFF_CREATED'
        when 8 then 'ACCESS_DENIED'
        else 'AUDIT_LOG_VIEWED'
    end,
    case when sequence % 19 = 0 then 'DENIED' else 'SUCCEEDED' end,
    case when sequence % 3 = 0 then 'TICKET' else 'AUDIT_ACTIVITY' end,
    md5('resource-' || (sequence % 100000))::uuid,
    md5('ticket-' || (sequence % 100000))::uuid,
    1000 + (sequence % 100000),
    md5('group-' || (sequence % 100))::uuid,
    case sequence % 4 when 0 then 'status' when 1 then 'priority' when 2 then 'groupId' else null end,
    case when sequence % 3 = 0 then '"OPEN"'::jsonb else null end,
    case when sequence % 3 = 0 then '"PENDING"'::jsonb else null end,
    jsonb_build_object('fixture', true),
    'request-' || sequence,
    'correlation-' || (sequence % 10000),
    md5('interaction-' || sequence)::uuid,
    'hmac-v1:synthetic-session-' || (sequence % 1000),
    'STAFF_SESSION',
    '192.0.2.' || (sequence % 255),
    'Deskseed performance fixture',
    case when sequence % 10 = 1 then md5('source-' || (sequence - 1))::uuid else null end,
    case when sequence % 10 = 0 then 's*** synthetic query' else null end,
    case when sequence % 10 = 0 then 'hmac-v2:fixture-' || (sequence % 1000) else null end,
    case when sequence % 10 = 0 then '{"status":"OPEN"}'::jsonb else null end,
    case when sequence % 10 = 0 then 'updatedAt:desc,ticketNumber:desc' else null end,
    case when sequence % 10 = 0 then sequence % 51 else null end,
    false,
    '2026-08-12 00:00:00+00'::timestamptz
from generate_series(1, 1000000) as fixture(sequence);

vacuum (analyze) audit_activity_projection;

select count(*) as fixture_rows from audit_activity_projection;
select
    pg_size_pretty(pg_relation_size('audit_activity_projection')) as table_size,
    pg_size_pretty(pg_indexes_size('audit_activity_projection')) as index_size,
    pg_size_pretty(pg_total_relation_size('audit_activity_projection')) as total_size;

\echo FIRST_CURSOR_PAGE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= '2026-08-05 00:00:00+00'
  and occurred_at <= '2026-08-12 00:00:00+00'
  and (occurred_at, id) <= (
      '2026-08-12 00:00:00+00'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
order by occurred_at desc, id desc
limit 51;

\echo ACTOR_AND_DATE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= '2026-08-05 00:00:00+00'
  and occurred_at <= '2026-08-12 00:00:00+00'
  and (occurred_at, id) <= (
      '2026-08-12 00:00:00+00'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and actor_id = md5('actor-42')::uuid
order by occurred_at desc, id desc
limit 51;

\echo TICKET_AND_DATE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= '2026-08-05 00:00:00+00'
  and occurred_at <= '2026-08-12 00:00:00+00'
  and (occurred_at, id) <= (
      '2026-08-12 00:00:00+00'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and ticket_number = 4242
order by occurred_at desc, id desc
limit 51;

\echo ACTION_AND_DATE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= '2026-08-05 00:00:00+00'
  and occurred_at <= '2026-08-12 00:00:00+00'
  and (occurred_at, id) <= (
      '2026-08-12 00:00:00+00'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and action = 'SEARCH_EXECUTED'
order by occurred_at desc, id desc
limit 51;
