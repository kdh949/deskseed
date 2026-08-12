\set ON_ERROR_STOP on
\pset pager off

-- These five queue statements mirror StaffTicketQueryRepository.list with
-- empty optional filters, cursor = null, and the API default limit + 1 (51).
-- DefaultStaffView currently exposes PENDING and RECENTLY_SOLVED; it does not
-- expose PENDING_OR_ON_HOLD or RECENTLY_UPDATED.

\echo QUEUE_MY_OPEN_FIRST_PAGE
explain (analyze, buffers, settings)
select t.id, t.ticket_number, t.subject, t.status, t.priority,
       t.updated_at, t.version, t.kind,
       (select count(*)
        from ticket_relations relation
        join tickets child on child.id = relation.target_ticket_id
        where relation.source_ticket_id = t.id
          and relation.relation_type = 'PARENT_CHILD'
          and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
       c.id as customer_id, c.name as customer_name,
       g.id as group_id, g.name as group_name,
       s.id as assignee_id, s.display_name as assignee_name
from tickets t
join customers c on c.id = t.requester_id
left join support_groups g on g.id = t.group_id
left join staff_accounts s on s.id = t.assignee_id
where t.status = 'OPEN'
  and t.assignee_id = md5(:'seed' || ':staff:42')::uuid
order by t.updated_at desc, t.ticket_number desc
limit 51;

\echo QUEUE_UNASSIGNED_MY_GROUPS_FIRST_PAGE
explain (analyze, buffers, settings)
select t.id, t.ticket_number, t.subject, t.status, t.priority,
       t.updated_at, t.version, t.kind,
       (select count(*)
        from ticket_relations relation
        join tickets child on child.id = relation.target_ticket_id
        where relation.source_ticket_id = t.id
          and relation.relation_type = 'PARENT_CHILD'
          and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
       c.id as customer_id, c.name as customer_name,
       g.id as group_id, g.name as group_name,
       s.id as assignee_id, s.display_name as assignee_name
from tickets t
join customers c on c.id = t.requester_id
left join support_groups g on g.id = t.group_id
left join staff_accounts s on s.id = t.assignee_id
where t.assignee_id is null
  and t.status not in ('SOLVED', 'CLOSED')
  and t.group_id in (
      select gm.group_id
      from group_memberships gm
      join support_groups mg on mg.id = gm.group_id and mg.status = 'ACTIVE'
      where gm.staff_id = md5(:'seed' || ':staff:42')::uuid
        and gm.status = 'ACTIVE'
  )
order by t.updated_at desc, t.ticket_number desc
limit 51;

\echo QUEUE_PENDING_FIRST_PAGE
explain (analyze, buffers, settings)
select t.id, t.ticket_number, t.subject, t.status, t.priority,
       t.updated_at, t.version, t.kind,
       (select count(*)
        from ticket_relations relation
        join tickets child on child.id = relation.target_ticket_id
        where relation.source_ticket_id = t.id
          and relation.relation_type = 'PARENT_CHILD'
          and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
       c.id as customer_id, c.name as customer_name,
       g.id as group_id, g.name as group_name,
       s.id as assignee_id, s.display_name as assignee_name
from tickets t
join customers c on c.id = t.requester_id
left join support_groups g on g.id = t.group_id
left join staff_accounts s on s.id = t.assignee_id
where t.status = 'PENDING'
order by t.updated_at desc, t.ticket_number desc
limit 51;

\echo QUEUE_RECENTLY_SOLVED_FIRST_PAGE
explain (analyze, buffers, settings)
select t.id, t.ticket_number, t.subject, t.status, t.priority,
       t.updated_at, t.version, t.kind,
       (select count(*)
        from ticket_relations relation
        join tickets child on child.id = relation.target_ticket_id
        where relation.source_ticket_id = t.id
          and relation.relation_type = 'PARENT_CHILD'
          and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
       c.id as customer_id, c.name as customer_name,
       g.id as group_id, g.name as group_name,
       s.id as assignee_id, s.display_name as assignee_name
from tickets t
join customers c on c.id = t.requester_id
left join support_groups g on g.id = t.group_id
left join staff_accounts s on s.id = t.assignee_id
where t.status = 'SOLVED'
  and t.assignee_id = md5(:'seed' || ':staff:42')::uuid
  and t.updated_at >= :'base_time'::timestamptz - interval '30 days'
order by t.updated_at desc, t.ticket_number desc
limit 51;

\echo QUEUE_MY_CHILD_TASKS_FIRST_PAGE
explain (analyze, buffers, settings)
select t.id, t.ticket_number, t.subject, t.status, t.priority,
       t.updated_at, t.version, t.kind,
       (select count(*)
        from ticket_relations relation
        join tickets child on child.id = relation.target_ticket_id
        where relation.source_ticket_id = t.id
          and relation.relation_type = 'PARENT_CHILD'
          and child.status not in ('SOLVED', 'CLOSED')) as open_child_count,
       c.id as customer_id, c.name as customer_name,
       g.id as group_id, g.name as group_name,
       s.id as assignee_id, s.display_name as assignee_name
from tickets t
join customers c on c.id = t.requester_id
left join support_groups g on g.id = t.group_id
left join staff_accounts s on s.id = t.assignee_id
where t.kind = 'INTERNAL_CHILD'
  and t.assignee_id = md5(:'seed' || ':staff:42')::uuid
  and t.status not in ('SOLVED', 'CLOSED')
order by t.updated_at desc, t.ticket_number desc
limit 51;

\echo AUDIT_FIRST_CURSOR_PAGE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= :'base_time'::timestamptz - interval '7 days'
  and occurred_at <= :'base_time'::timestamptz
  and (occurred_at, id) <= (
      :'base_time'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
order by occurred_at desc, id desc
limit 51;

\echo STAFF_COMMAND_REPLAY_LOOKUP
explain (analyze, buffers, settings)
select audit.id as audit_id, ticket.ticket_number, audit.ticket_version,
       first_event.metadata_json::jsonb ->> 'commandOperation' as command_operation,
       first_event.metadata_json::jsonb ->> 'commandRequestDescriptor' as request_descriptor,
       coalesce(
           (
               select event.metadata_json::jsonb -> 'commandWarnings'
               from ticket_audit_events event
               where event.audit_id = audit.id
               order by event.event_order
               limit 1
           ),
           '[]'::jsonb
       )::text as warnings_json
from ticket_audits audit
join tickets ticket on ticket.id = audit.ticket_id
left join lateral (
    select event.metadata_json
    from ticket_audit_events event
    where event.audit_id = audit.id
    order by event.event_order
    limit 1
) first_event on true
where audit.actor_type = 'STAFF'
  and audit.actor_id = md5(:'seed' || ':staff:42')::uuid
  and audit.command_id = 'perf-command-42'
order by audit.created_at, audit.id
limit 2;

\echo AUDIT_ACTOR_AND_DATE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= :'base_time'::timestamptz - interval '7 days'
  and occurred_at <= :'base_time'::timestamptz
  and (occurred_at, id) <= (
      :'base_time'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and actor_id = md5(:'seed' || ':staff:42')::uuid
order by occurred_at desc, id desc
limit 51;

\echo AUDIT_TICKET_AND_DATE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= :'base_time'::timestamptz - interval '7 days'
  and occurred_at <= :'base_time'::timestamptz
  and (occurred_at, id) <= (
      :'base_time'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and ticket_number = 5242
order by occurred_at desc, id desc
limit 51;

\echo AUDIT_ACTION_AND_DATE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= :'base_time'::timestamptz - interval '7 days'
  and occurred_at <= :'base_time'::timestamptz
  and (occurred_at, id) <= (
      :'base_time'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and action = 'STATUS_CHANGED'
order by occurred_at desc, id desc
limit 51;
