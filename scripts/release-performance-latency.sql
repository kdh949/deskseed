\set ON_ERROR_STOP on

create temporary table release_performance_samples (
    phase text not null,
    query_name text not null,
    sample_number integer not null,
    elapsed_ms double precision not null
) on commit preserve rows;

create or replace function pg_temp.run_release_performance(
    p_phase text,
    p_repetitions integer,
    p_seed text,
    p_base_time timestamptz
)
returns table (
    query_name text,
    phase text,
    samples bigint,
    min_ms numeric,
    p50_ms numeric,
    p95_ms numeric,
    max_ms numeric
)
language plpgsql
as $$
declare
    query_names text[] := array[
        'queue_my_open_first_page',
        'queue_unassigned_my_groups_first_page',
        'queue_pending_first_page',
        'queue_recently_solved_first_page',
        'queue_my_child_tasks_first_page',
        'audit_first_cursor_page',
        'staff_command_replay_lookup',
        'audit_actor_and_date',
        'audit_ticket_and_date',
        'audit_action_and_date'
    ];
    queries text[];
    query_index integer;
    sample_index integer;
    started_at timestamptz;
    elapsed double precision;
    returned_rows bigint;
begin
    if p_repetitions < 3 or p_repetitions > 100 then
        raise exception 'repetitions must be between 3 and 100';
    end if;

    -- Queue statements mirror StaffTicketQueryRepository.list with empty
    -- optional filters, cursor = null, and API default limit + 1 (51).
    queries := array[
        format($query$
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
              and t.assignee_id = %L::uuid
            order by t.updated_at desc, t.ticket_number desc
            limit 51
        $query$, md5(p_seed || ':staff:42')::uuid),
        format($query$
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
                  where gm.staff_id = %L::uuid and gm.status = 'ACTIVE'
              )
            order by t.updated_at desc, t.ticket_number desc
            limit 51
        $query$, md5(p_seed || ':staff:42')::uuid),
        $query$
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
            limit 51
        $query$,
        format($query$
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
              and t.assignee_id = %L::uuid
              and t.updated_at >= %L::timestamptz
            order by t.updated_at desc, t.ticket_number desc
            limit 51
        $query$, md5(p_seed || ':staff:42')::uuid, p_base_time - interval '30 days'),
        format($query$
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
              and t.assignee_id = %L::uuid
              and t.status not in ('SOLVED', 'CLOSED')
            order by t.updated_at desc, t.ticket_number desc
            limit 51
        $query$, md5(p_seed || ':staff:42')::uuid),
        format($query$
            select *
            from audit_activity_projection
            where occurred_at >= %L::timestamptz
              and occurred_at <= %L::timestamptz
              and (occurred_at, id) <= (%L::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
            order by occurred_at desc, id desc
            limit 51
        $query$, p_base_time - interval '7 days', p_base_time, p_base_time),
        format($query$
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
              and audit.actor_id = %L::uuid
              and audit.command_id = 'perf-command-42'
            order by audit.created_at, audit.id
            limit 2
        $query$, md5(p_seed || ':staff:42')::uuid),
        format($query$
            select *
            from audit_activity_projection
            where occurred_at >= %L::timestamptz
              and occurred_at <= %L::timestamptz
              and (occurred_at, id) <= (%L::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
              and actor_id = %L::uuid
            order by occurred_at desc, id desc
            limit 51
        $query$, p_base_time - interval '7 days', p_base_time, p_base_time,
            md5(p_seed || ':staff:42')::uuid),
        format($query$
            select *
            from audit_activity_projection
            where occurred_at >= %L::timestamptz
              and occurred_at <= %L::timestamptz
              and (occurred_at, id) <= (%L::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
              and ticket_number = 5242
            order by occurred_at desc, id desc
            limit 51
        $query$, p_base_time - interval '7 days', p_base_time, p_base_time),
        format($query$
            select *
            from audit_activity_projection
            where occurred_at >= %L::timestamptz
              and occurred_at <= %L::timestamptz
              and (occurred_at, id) <= (%L::timestamptz, 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)
              and action = 'STATUS_CHANGED'
            order by occurred_at desc, id desc
            limit 51
        $query$, p_base_time - interval '7 days', p_base_time, p_base_time)
    ];

    for query_index in 1..array_length(queries, 1) loop
        -- One unrecorded execution establishes the documented warm-cache measurement.
        execute queries[query_index];
        get diagnostics returned_rows = row_count;
        if returned_rows < 1 then
            raise exception 'performance query % returned zero rows', query_names[query_index];
        end if;
        for sample_index in 1..p_repetitions loop
            started_at := clock_timestamp();
            execute queries[query_index];
            elapsed := extract(epoch from clock_timestamp() - started_at) * 1000.0;
            insert into release_performance_samples (
                phase, query_name, sample_number, elapsed_ms
            ) values (
                p_phase, query_names[query_index], sample_index, elapsed
            );
        end loop;
    end loop;

    return query
    select
        sample.query_name,
        sample.phase,
        count(*) as samples,
        round(min(sample.elapsed_ms)::numeric, 3) as min_ms,
        round(
            (percentile_cont(0.50) within group (order by sample.elapsed_ms))::numeric,
            3
        ) as p50_ms,
        round(
            (percentile_cont(0.95) within group (order by sample.elapsed_ms))::numeric,
            3
        ) as p95_ms,
        round(max(sample.elapsed_ms)::numeric, 3) as max_ms
    from release_performance_samples sample
    where sample.phase = p_phase
    group by sample.query_name, sample.phase
    order by sample.query_name;
end;
$$;

select *
from pg_temp.run_release_performance(
    :'phase',
    :repetitions,
    :'seed',
    :'base_time'::timestamptz
);
