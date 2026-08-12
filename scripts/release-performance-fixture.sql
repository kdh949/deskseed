\set ON_ERROR_STOP on
\timing on

-- Release defaults. The runner overrides these only for the documented smoke profile
-- or when an operator supplies a validated PERF_* environment variable.
\if :{?seed}
\else
\set seed 424242
\endif
\if :{?base_time}
\else
\set base_time '2026-08-12T00:00:00Z'
\endif
\if :{?customer_count}
\else
\set customer_count 100000
\endif
\if :{?ticket_count}
\else
\set ticket_count 1000000
\endif
\if :{?comments_per_ticket}
\else
\set comments_per_ticket 2
\endif
\if :{?ticket_audit_count}
\else
\set ticket_audit_count 1000000
\endif
\if :{?access_audit_count}
\else
\set access_audit_count 500000
\endif
\if :{?admin_audit_count}
\else
\set admin_audit_count 100000
\endif
\if :{?staff_count}
\else
\set staff_count 1000
\endif
\if :{?group_count}
\else
\set group_count 100
\endif

select (
    :customer_count::bigint >= 1
    and :ticket_count::bigint >= 100
    and :comments_per_ticket::bigint >= 1
    and :ticket_audit_count::bigint >= 1
    and :ticket_audit_count::bigint <= :ticket_count::bigint
    and :access_audit_count::bigint >= 10
    and :admin_audit_count::bigint >= 1
    and :staff_count::bigint >= 42
    and :group_count::bigint >= 1
    and :group_count::bigint <= :staff_count::bigint
) as fixture_parameters_valid \gset

\if :fixture_parameters_valid
\else
select 1 / 0 as invalid_release_performance_fixture_parameters;
\endif

set synchronous_commit = off;
set client_min_messages = warning;

\echo FIXTURE_SUPPORT_GROUPS
insert into support_groups (
    id, name, status, created_at, updated_at, version
)
select
    md5(:'seed' || ':group:' || sequence)::uuid,
    'Performance group ' || sequence,
    'ACTIVE',
    :'base_time'::timestamptz - interval '90 days',
    :'base_time'::timestamptz - interval '90 days',
    0
from generate_series(1, :group_count) as fixture(sequence);

\echo FIXTURE_STAFF
insert into staff_accounts (
    id, email_normalized, email_display, display_name, role, status,
    password_hash, created_at, updated_at, last_login_at, version
)
select
    md5(:'seed' || ':staff:' || sequence)::uuid,
    'performance-staff-' || sequence || '@example.invalid',
    'performance-staff-' || sequence || '@example.invalid',
    'Performance staff ' || sequence,
    case when sequence = 1 then 'ADMIN' else 'AGENT' end,
    'ACTIVE',
    'PERFORMANCE_FIXTURE_DISABLED_LOGIN',
    :'base_time'::timestamptz - interval '90 days',
    :'base_time'::timestamptz - interval '90 days',
    null,
    0
from generate_series(1, :staff_count) as fixture(sequence);

insert into group_memberships (
    id, group_id, staff_id, status, created_at, updated_at, version
)
select
    md5(:'seed' || ':membership:' || sequence)::uuid,
    md5(:'seed' || ':group:' || (((sequence - 1) % :group_count) + 1))::uuid,
    md5(:'seed' || ':staff:' || sequence)::uuid,
    'ACTIVE',
    :'base_time'::timestamptz - interval '90 days',
    :'base_time'::timestamptz - interval '90 days',
    0
from generate_series(1, :staff_count) as fixture(sequence);

\echo FIXTURE_CUSTOMERS
insert into customers (
    id, name, email_normalized, email_display, verified_at, created_at, updated_at
)
select
    md5(:'seed' || ':customer:' || sequence)::uuid,
    'Performance customer ' || sequence,
    'performance-customer-' || sequence || '@example.invalid',
    'performance-customer-' || sequence || '@example.invalid',
    :'base_time'::timestamptz - interval '60 days',
    :'base_time'::timestamptz - interval '60 days',
    :'base_time'::timestamptz - interval '60 days'
from generate_series(1, :customer_count) as fixture(sequence);

\echo FIXTURE_TICKETS
insert into tickets (
    id, ticket_number, requester_id, kind, subject, status, priority,
    group_id, assignee_id, channel, version, created_at, updated_at, solved_at
)
select
    md5(:'seed' || ':ticket:' || sequence)::uuid,
    1000 + sequence,
    md5(:'seed' || ':customer:' || (((sequence - 1) % :customer_count) + 1))::uuid,
    case when sequence % 100 = 0 then 'INTERNAL_CHILD' else 'CUSTOMER_REQUEST' end,
    'Performance ticket ' || sequence,
    case ((sequence - 1) / :staff_count) % 5
        when 0 then 'OPEN'
        when 1 then 'NEW'
        when 2 then 'PENDING'
        when 3 then 'ON_HOLD'
        else 'SOLVED'
    end,
    case sequence % 4
        when 0 then 'LOW'
        when 1 then 'NORMAL'
        when 2 then 'HIGH'
        else 'URGENT'
    end,
    case
        -- Give every deterministic actor a bounded child-task population. Child
        -- rows must be assigned before the generic sequence-based unassigned
        -- distribution, otherwise MY_CHILD_TASKS has zero representative rows.
        when sequence % 100 = 0 then
            md5(
                :'seed' || ':group:' ||
                ((((42 - 1) % :group_count) + 1))
            )::uuid
        -- Keep a bounded ungrouped population, and distribute the remaining
        -- unassigned tickets across active groups so UNASSIGNED_MY_GROUPS has
        -- representative cardinality for every deterministic actor group.
        when sequence % 20 = 0 then null
        when sequence % 5 = 0 then
            md5(
                :'seed' || ':group:' ||
                (((sequence / 5 - 1) % :group_count) + 1)
            )::uuid
        else
            md5(
                :'seed' || ':group:' ||
                (((((sequence - 1) % :staff_count) + 1) - 1) % :group_count + 1)
            )::uuid
    end,
    case
        when sequence % 100 = 0 then md5(:'seed' || ':staff:42')::uuid
        when sequence % 5 = 0 then null
        else md5(:'seed' || ':staff:' || (((sequence - 1) % :staff_count) + 1))::uuid
    end,
    case sequence % 3 when 0 then 'WEB' when 1 then 'AGENT' else 'API' end,
    1,
    :'base_time'::timestamptz - interval '30 days',
    :'base_time'::timestamptz - ((sequence % 2592000) * interval '1 second'),
    case
        when ((sequence - 1) / :staff_count) % 5 = 4
        then :'base_time'::timestamptz - ((sequence % 2592000) * interval '1 second')
        else null
    end
from generate_series(1, :ticket_count) as fixture(sequence);

select setval('ticket_number_seq', 1000 + :ticket_count, true);

insert into ticket_relations (
    id, source_ticket_id, target_ticket_id, relation_type,
    created_by_actor_type, created_by_actor_id, created_at
)
select
    md5(:'seed' || ':relation:' || child_sequence)::uuid,
    md5(:'seed' || ':ticket:' || (child_sequence - 1))::uuid,
    md5(:'seed' || ':ticket:' || child_sequence)::uuid,
    'PARENT_CHILD',
    'STAFF',
    md5(:'seed' || ':staff:' || (((child_sequence - 1) % :staff_count) + 1))::uuid,
    :'base_time'::timestamptz - ((child_sequence % 2592000) * interval '1 second')
from generate_series(100, :ticket_count, 100) as fixture(child_sequence);

\echo FIXTURE_COMMENTS
insert into ticket_comments (
    id, ticket_id, author_type, author_id, visibility, body, created_at
)
select
    md5(:'seed' || ':comment:' || ticket_sequence || ':' || comment_sequence)::uuid,
    md5(:'seed' || ':ticket:' || ticket_sequence)::uuid,
    case when comment_sequence % 2 = 1 then 'CUSTOMER' else 'AGENT' end,
    case
        when comment_sequence % 2 = 1
        then md5(:'seed' || ':customer:' || (((ticket_sequence - 1) % :customer_count) + 1))::uuid
        else md5(:'seed' || ':staff:' || (((ticket_sequence - 1) % :staff_count) + 1))::uuid
    end,
    case when comment_sequence % 2 = 1 then 'PUBLIC' else 'INTERNAL' end,
    'Synthetic ' ||
        case when comment_sequence % 2 = 1 then 'public' else 'internal' end ||
        ' performance comment ' || ticket_sequence || '-' || comment_sequence,
    :'base_time'::timestamptz
        - ((ticket_sequence % 2592000) * interval '1 second')
        + (comment_sequence * interval '1 millisecond')
from generate_series(1, :ticket_count) as tickets(ticket_sequence)
cross join generate_series(1, :comments_per_ticket) as comments(comment_sequence);

-- Canonical rows are loaded with all integrity constraints active. Only the five
-- per-row projection refresh triggers are paused; one canonical rebuild follows.
alter table ticket_audit_events disable trigger ticket_audit_events_projected;
alter table access_audit_events disable trigger access_audit_events_projected;
alter table search_audit_details disable trigger search_audit_details_projected;
alter table search_audit_query_ciphertexts disable trigger search_audit_query_ciphertexts_projected;
alter table admin_security_audit_events disable trigger admin_security_audit_events_projected;

\echo FIXTURE_TICKET_AUDITS
insert into ticket_audits (
    id, ticket_id, ticket_version, actor_type, actor_id, source, created_at,
    request_id, correlation_id, command_id, expected_version,
    actor_display_snapshot, group_id
)
select
    md5(:'seed' || ':ticket-audit:' || sequence)::uuid,
    md5(:'seed' || ':ticket:' || sequence)::uuid,
    1,
    'STAFF',
    md5(:'seed' || ':staff:' || (((sequence - 1) % :staff_count) + 1))::uuid,
    'AGENT_UI',
    :'base_time'::timestamptz - ((sequence % 2592000) * interval '1 second'),
    'perf-request-' || sequence,
    'perf-correlation-' || (sequence % 10000),
    'perf-command-' || sequence,
    0,
    staff.display_name,
    ticket.group_id
from generate_series(1, :ticket_audit_count) as fixture(sequence)
join tickets ticket
  on ticket.id = md5(:'seed' || ':ticket:' || sequence)::uuid
join staff_accounts staff
  on staff.id = md5(:'seed' || ':staff:' || (((sequence - 1) % :staff_count) + 1))::uuid;

insert into ticket_audit_events (
    id, audit_id, event_order, event_type, field_name,
    old_value_json, new_value_json, metadata_json, occurred_at
)
select
    md5(:'seed' || ':ticket-audit-event:' || sequence)::uuid,
    md5(:'seed' || ':ticket-audit:' || sequence)::uuid,
    0,
    case sequence % 4
        when 0 then 'STATUS_CHANGED'
        when 1 then 'PRIORITY_CHANGED'
        when 2 then 'GROUP_CHANGED'
        else 'COMMENT_ADDED'
    end,
    case sequence % 4
        when 0 then 'status'
        when 1 then 'priority'
        when 2 then 'groupId'
        else null
    end,
    case sequence % 4
        when 0 then '"NEW"'
        when 1 then '"NORMAL"'
        else null
    end,
    case sequence % 4
        when 0 then '"OPEN"'
        when 1 then '"HIGH"'
        when 2 then to_json(md5(:'seed' || ':group:' || ((sequence % :group_count) + 1))::text)::text
        else null
    end,
    '{"fixture":true}',
    :'base_time'::timestamptz - ((sequence % 2592000) * interval '1 second')
from generate_series(1, :ticket_audit_count) as fixture(sequence);

\echo FIXTURE_ACCESS_AUDITS
insert into access_audit_events (
    id, occurred_at, actor_type, actor_id, actor_display_snapshot, source,
    action, resource_type, resource_id, ticket_number, interaction_id,
    request_id, correlation_id, ip_address, user_agent, outcome, http_status,
    session_fingerprint, auth_type, origin_search_event_id, group_id
)
select
    md5(:'seed' || ':access-audit:' || sequence)::uuid,
    :'base_time'::timestamptz - ((sequence % 2592000) * interval '1 second'),
    'STAFF',
    md5(:'seed' || ':staff:' || (((sequence - 1) % :staff_count) + 1))::uuid,
    'Performance staff ' || (((sequence - 1) % :staff_count) + 1),
    'AGENT_UI',
    case when sequence % 10 = 0 then 'SEARCH_EXECUTED' else 'TICKET_VIEWED' end,
    case when sequence % 10 = 0 then 'SEARCH' else 'TICKET' end,
    case when sequence % 10 = 0 then null else
        md5(:'seed' || ':ticket:' || (((sequence - 1) % :ticket_count) + 1))::uuid
    end,
    case when sequence % 10 = 0 then null else
        1000 + (((sequence - 1) % :ticket_count) + 1)
    end,
    md5(:'seed' || ':interaction:' || sequence)::uuid,
    'perf-access-request-' || sequence,
    'perf-access-correlation-' || (sequence % 10000),
    '192.0.2.' || ((sequence % 254) + 1),
    'Deskseed release performance fixture',
    case when sequence % 19 = 0 then 'DENIED' else 'SUCCEEDED' end,
    case when sequence % 19 = 0 then 403 else 200 end,
    'hmac-v1:performance-session-' || (sequence % :staff_count),
    'STAFF_SESSION',
    null,
    access_ticket.group_id
from generate_series(1, :access_audit_count) as fixture(sequence)
left join tickets access_ticket
  on access_ticket.id = case when sequence % 10 = 0 then null else
      md5(:'seed' || ':ticket:' || (((sequence - 1) % :ticket_count) + 1))::uuid
  end;

insert into search_audit_details (
    access_event_id, query_redacted, query_fingerprint, query_key_version,
    normalized_filters, sort, result_count
)
select
    md5(:'seed' || ':access-audit:' || sequence)::uuid,
    '[PROTECTED]',
    'hmac-v2:performance-' || (sequence % 1000),
    'performance-v2',
    jsonb_build_object('status', 'OPEN'),
    'updatedAt:desc,ticketNumber:desc',
    sequence % 51
from generate_series(10, :access_audit_count, 10) as fixture(sequence);

\echo FIXTURE_ADMIN_AUDITS
insert into admin_security_audit_events (
    id, event_type, actor_type, actor_id, actor_display_snapshot, source,
    target_type, target_id, outcome, request_id, correlation_id,
    metadata_json, occurred_at
)
select
    md5(:'seed' || ':admin-audit:' || sequence)::uuid,
    case sequence % 4
        when 0 then 'AUDIT_LOG_VIEWED'
        when 1 then 'STAFF_CREATED'
        when 2 then 'LOGIN_SUCCEEDED'
        else 'AUDIT_EXPORT_REQUESTED'
    end,
    'STAFF',
    md5(:'seed' || ':staff:' || (((sequence - 1) % :staff_count) + 1))::uuid,
    'Performance staff ' || (((sequence - 1) % :staff_count) + 1),
    case when sequence % 4 = 0 then 'ADMIN_UI' else 'AGENT_UI' end,
    case sequence % 3 when 0 then 'TICKET' when 1 then 'STAFF_ACCOUNT' else 'AUDIT_ACTIVITY' end,
    md5(:'seed' || ':admin-target:' || sequence)::uuid,
    case when sequence % 23 = 0 then 'DENIED' else 'SUCCEEDED' end,
    'perf-admin-request-' || sequence,
    'perf-admin-correlation-' || (sequence % 10000),
    jsonb_build_object(
        'fixture', true,
        'field', case sequence % 3 when 0 then 'status' else 'role' end,
        'groupId', md5(:'seed' || ':group:' || ((sequence % :group_count) + 1))::text
    )::text,
    :'base_time'::timestamptz - ((sequence % 2592000) * interval '1 second')
from generate_series(1, :admin_audit_count) as fixture(sequence);

alter table ticket_audit_events enable trigger ticket_audit_events_projected;
alter table access_audit_events enable trigger access_audit_events_projected;
alter table search_audit_details enable trigger search_audit_details_projected;
alter table search_audit_query_ciphertexts enable trigger search_audit_query_ciphertexts_projected;
alter table admin_security_audit_events enable trigger admin_security_audit_events_projected;

\echo REBUILD_AUDIT_ACTIVITY_PROJECTION
select * from rebuild_audit_activity_projection();

\echo VACUUM_ANALYZE_RELEASE_FIXTURE
vacuum (analyze) customers;
vacuum (analyze) tickets;
vacuum (analyze) ticket_comments;
vacuum (analyze) ticket_audits;
vacuum (analyze) ticket_audit_events;
vacuum (analyze) access_audit_events;
vacuum (analyze) search_audit_details;
vacuum (analyze) admin_security_audit_events;
vacuum (analyze) audit_activity_projection;
