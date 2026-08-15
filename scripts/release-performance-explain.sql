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

-- These two statements mirror StaffTicketQueryRepository.search for the P1
-- score/ticketNumber cursor mode with a numeric query. They retain the SQL
-- authorization predicate and the same matching/score expression as runtime.
\set search_query '6242'

\echo SEARCH_AGENT_WORKSPACE_EXACT_COUNT
explain (analyze, buffers, settings)
select count(*)
from tickets t
left join customers c on c.id = t.requester_id
left join support_groups g on g.id = t.group_id
left join staff_accounts s on s.id = t.assignee_id
left join analytics_first_reply_facts fact on fact.ticket_id = t.id
where exists (
    select 1 from staff_accounts authorized_actor
    where authorized_actor.id = md5(:'seed' || ':staff:42')::uuid
      and authorized_actor.status = 'ACTIVE'
)
  and t.updated_at <= :'base_time'::timestamptz
  and (
      (cast(:'search_query' as bigint) is not null
          and t.ticket_number = cast(:'search_query' as bigint))
      or strpos(lower(t.subject), lower(:'search_query')) > 0
      or strpos(lower(c.name), lower(:'search_query')) > 0
      or strpos(lower(c.email_normalized), lower(:'search_query')) > 0
      or strpos(lower(g.name), lower(:'search_query')) > 0
      or strpos(lower(s.display_name), lower(:'search_query')) > 0
      or exists (
          select 1 from ticket_comments search_comment
          where search_comment.ticket_id = t.id
            and strpos(lower(search_comment.body), lower(:'search_query')) > 0
      )
  );

\echo SEARCH_AGENT_WORKSPACE_SCORE_FIRST_PAGE
explain (analyze, buffers, settings)
with ranked as (
    select t.id, t.ticket_number, t.subject, t.status, t.priority,
           t.created_at, t.updated_at, t.version, t.kind,
           c.id as customer_id, c.name as customer_name,
           g.id as group_id, g.name as group_name,
           s.id as assignee_id, s.display_name as assignee_name,
           fact.outcome as sla_outcome, fact.due_at as sla_due_at,
           fact.target_minutes as sla_target_minutes, fact.policy_version as sla_policy_version,
           fact.schedule_version as sla_schedule_version,
           (
               case when cast(:'search_query' as bigint) is not null
                           and t.ticket_number = cast(:'search_query' as bigint) then 1000 else 0 end
               + case when lower(t.subject) = lower(:'search_query') then 500
                      when strpos(lower(t.subject), lower(:'search_query')) > 0 then 250 else 0 end
               + case when lower(c.name) = lower(:'search_query') then 180
                      when strpos(lower(c.name), lower(:'search_query')) > 0 then 90 else 0 end
               + case when lower(c.email_normalized) = lower(:'search_query') then 160
                      when strpos(lower(c.email_normalized), lower(:'search_query')) > 0 then 80 else 0 end
               + case when lower(g.name) = lower(:'search_query') then 80
                      when strpos(lower(g.name), lower(:'search_query')) > 0 then 40 else 0 end
               + case when lower(s.display_name) = lower(:'search_query') then 80
                      when strpos(lower(s.display_name), lower(:'search_query')) > 0 then 40 else 0 end
               + case when exists (
                       select 1 from ticket_comments scored_comment
                       where scored_comment.ticket_id = t.id
                         and strpos(lower(scored_comment.body), lower(:'search_query')) > 0
                   ) then 20 else 0 end
           ) as search_score
    from tickets t
    left join customers c on c.id = t.requester_id
    left join support_groups g on g.id = t.group_id
    left join staff_accounts s on s.id = t.assignee_id
    left join analytics_first_reply_facts fact on fact.ticket_id = t.id
    where exists (
        select 1 from staff_accounts authorized_actor
        where authorized_actor.id = md5(:'seed' || ':staff:42')::uuid
          and authorized_actor.status = 'ACTIVE'
    )
      and t.updated_at <= :'base_time'::timestamptz
      and (
          (cast(:'search_query' as bigint) is not null
              and t.ticket_number = cast(:'search_query' as bigint))
          or strpos(lower(t.subject), lower(:'search_query')) > 0
          or strpos(lower(c.name), lower(:'search_query')) > 0
          or strpos(lower(c.email_normalized), lower(:'search_query')) > 0
          or strpos(lower(g.name), lower(:'search_query')) > 0
          or strpos(lower(s.display_name), lower(:'search_query')) > 0
          or exists (
              select 1 from ticket_comments search_comment
              where search_comment.ticket_id = t.id
                and strpos(lower(search_comment.body), lower(:'search_query')) > 0
          )
      )
)
select *
from ranked
order by search_score desc, ticket_number desc
limit 51;

\echo AUDIT_FIRST_CURSOR_PAGE
explain (analyze, buffers, settings)
select *
from audit_activity_projection
where occurred_at >= :'base_time'::timestamptz - interval '7 days'
  and occurred_at < :'base_time'::timestamptz
  and (occurred_at, id) <= (
      :'base_time'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
order by occurred_at desc, id desc
limit 51;

\echo AUDIT_PROJECTION_STATUS
explain (analyze, buffers, settings)
select
       case
           when pg_try_advisory_xact_lock_shared(hashtext('deskseed:audit-activity-projection:rebuild'))
               then state
           else 'REBUILDING'
       end as state,
       last_rebuilt_at,
       projected_count
from audit_activity_projection_state
where id = 1;

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
  and occurred_at < :'base_time'::timestamptz
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
  and occurred_at < :'base_time'::timestamptz
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
  and occurred_at < :'base_time'::timestamptz
  and (occurred_at, id) <= (
      :'base_time'::timestamptz,
      'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid
  )
  and action = 'STATUS_CHANGED'
order by occurred_at desc, id desc
limit 51;
