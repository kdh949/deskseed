\set ON_ERROR_STOP on

-- PERF-003 database-component benchmark. Each timed sample commits the exact
-- three StaffTicketQueryRepository.findDetail statements for ticket 5242.
-- The audited phase adds the exact JpaAccessAuditWriter API_RESOURCE_READ
-- insert; the production projection trigger runs in the same transaction.

set synchronous_commit = on;

create temporary table access_overhead_samples (
    phase text not null,
    sample_number integer not null,
    elapsed_ms double precision not null
) on commit preserve rows;

create temporary table access_overhead_results (
    phase text not null,
    samples bigint not null,
    p50_ms numeric not null,
    p95_ms numeric not null,
    throughput_ops_per_second numeric not null,
    canonical_rows_written bigint not null,
    projection_rows_written bigint not null,
    total_rows_written bigint not null,
    relation_bytes_delta bigint not null,
    wal_bytes_delta bigint not null
) on commit preserve rows;

create or replace procedure pg_temp.run_sensitive_ticket_detail_samples(
    p_phase text,
    p_with_required_access_audit boolean,
    p_repetitions integer,
    p_seed text,
    p_base_time timestamptz,
    p_record_samples boolean
)
language plpgsql
as $$
declare
    sample_index integer;
    started_at timestamptz;
    elapsed double precision;
    requested_ticket_id uuid := md5(p_seed || ':ticket:4242')::uuid;
    authorized_actor_id uuid := md5(p_seed || ':staff:42')::uuid;
begin
    if p_repetitions < 1 or p_repetitions > 1000 then
        raise exception 'access overhead repetitions must be between 1 and 1000';
    end if;

    for sample_index in 1..p_repetitions loop
        started_at := clock_timestamp();

        perform t.id, t.ticket_number, t.subject, t.status, t.priority,
                t.updated_at, t.version, t.kind,
                (select count(*)
                 from ticket_relations relation
                 join tickets child on child.id = relation.target_ticket_id
                 where relation.source_ticket_id = t.id
                   and relation.relation_type = 'PARENT_CHILD'
                   and child.status not in ('SOLVED', 'CLOSED')),
                c.id, c.name, c.email_display,
                g.id, g.name,
                s.id, s.display_name,
                linked.direction,
                rt.id, rt.ticket_number, rt.subject, rt.status,
                rt.priority, rt.updated_at, rt.version, rt.kind,
                (select count(*)
                 from ticket_relations open_relation
                 join tickets open_child on open_child.id = open_relation.target_ticket_id
                 where open_relation.source_ticket_id = rt.id
                   and open_relation.relation_type = 'PARENT_CHILD'
                   and open_child.status not in ('SOLVED', 'CLOSED')),
                rc.id, rc.name,
                rg.id, rg.name,
                rs.id, rs.display_name
        from tickets t
        join customers c on c.id = t.requester_id
        left join support_groups g on g.id = t.group_id
        left join staff_accounts s on s.id = t.assignee_id
        left join lateral (
            select 'PARENT' as direction, relation.source_ticket_id as related_ticket_id
            from ticket_relations relation
            where relation.target_ticket_id = t.id
              and relation.relation_type = 'PARENT_CHILD'
            union all
            select 'CHILD' as direction, relation.target_ticket_id as related_ticket_id
            from ticket_relations relation
            where relation.source_ticket_id = t.id
              and relation.relation_type = 'PARENT_CHILD'
        ) linked on true
        left join tickets rt on rt.id = linked.related_ticket_id
        left join customers rc on rc.id = rt.requester_id
        left join support_groups rg on rg.id = rt.group_id
        left join staff_accounts rs on rs.id = rt.assignee_id
        where t.ticket_number = 5242
        order by linked.direction desc, rt.ticket_number;

        perform tc.id, tc.visibility, tc.author_type, tc.author_id, tc.body, tc.created_at,
                coalesce(
                    comment_customer.name,
                    comment_staff.display_name,
                    case tc.author_type when 'SYSTEM' then 'Deskseed' else '자동화' end
                )
        from ticket_comments tc
        left join customers comment_customer
          on tc.author_type = 'CUSTOMER' and comment_customer.id = tc.author_id
        left join staff_accounts comment_staff
          on tc.author_type = 'AGENT' and comment_staff.id = tc.author_id
        where tc.ticket_id = requested_ticket_id
        order by tc.created_at, tc.id;

        perform audit_event.id, audit_event.event_type, audit_event.occurred_at,
                ticket_audit.actor_type, ticket_audit.actor_id,
                coalesce(
                    audit_customer.name,
                    audit_staff.display_name,
                    case ticket_audit.actor_type
                        when 'SYSTEM' then 'Deskseed'
                        else ticket_audit.actor_type
                    end
                )
        from ticket_audits ticket_audit
        join ticket_audit_events audit_event on audit_event.audit_id = ticket_audit.id
        left join customers audit_customer
          on ticket_audit.actor_type = 'CUSTOMER'
         and audit_customer.id = ticket_audit.actor_id
        left join staff_accounts audit_staff
          on ticket_audit.actor_type = 'STAFF'
         and audit_staff.id = ticket_audit.actor_id
        where ticket_audit.ticket_id = requested_ticket_id
        order by audit_event.occurred_at, ticket_audit.id, audit_event.event_order;

        if p_with_required_access_audit then
            insert into access_audit_events (
                id, occurred_at, actor_type, actor_id, actor_display_snapshot,
                source, action, resource_type, resource_id, ticket_number,
                interaction_id, session_fingerprint, auth_type, request_id,
                correlation_id, ip_address, user_agent, outcome, http_status
            ) values (
                md5(p_seed || ':perf-access:' || p_phase || ':' || sample_index)::uuid,
                p_base_time + sample_index * interval '1 microsecond',
                'STAFF',
                authorized_actor_id,
                'Performance staff 42',
                'AGENT_UI',
                'API_RESOURCE_READ',
                'TICKET',
                requested_ticket_id,
                5242,
                md5(p_seed || ':perf-interaction:' || p_phase || ':' || sample_index)::uuid,
                'performance-session-fingerprint',
                'STAFF_SESSION',
                left('perf-overhead-' || p_phase || '-' || sample_index, 100),
                left('perf-overhead-correlation-' || p_phase || '-' || sample_index, 100),
                '127.0.0.1',
                'Deskseed release performance harness',
                'SUCCEEDED',
                200
            );
        end if;

        -- Include the real transaction commit and synchronous WAL flush in the
        -- measured request component. The sample-table write below is temporary
        -- and occurs after the timer stops.
        commit;
        elapsed := extract(epoch from clock_timestamp() - started_at) * 1000.0;

        if p_record_samples then
            insert into access_overhead_samples (phase, sample_number, elapsed_ms)
            values (p_phase, sample_index, elapsed);
            commit;
        end if;
    end loop;
end;
$$;

-- Establish both code paths once before their measured runs. The audited
-- warm-up is outside the recorded row/byte/WAL deltas.
call pg_temp.run_sensitive_ticket_detail_samples(
    'without_audit_warmup', false, 1, :'seed', :'base_time'::timestamptz, false
);

select
    pg_current_wal_insert_lsn()::text as wal_lsn,
    (
        pg_total_relation_size('access_audit_events')
        + pg_total_relation_size('audit_activity_projection')
    )::bigint as relation_bytes
\gset base_before_

call pg_temp.run_sensitive_ticket_detail_samples(
    'without_required_access_audit',
    false,
    :access_repetitions,
    :'seed',
    :'base_time'::timestamptz,
    true
);

insert into access_overhead_results
select
    'without_required_access_audit',
    count(*),
    round((percentile_cont(0.50) within group (order by elapsed_ms))::numeric, 3),
    round((percentile_cont(0.95) within group (order by elapsed_ms))::numeric, 3),
    round((count(*) * 1000.0 / sum(elapsed_ms))::numeric, 3),
    0,
    0,
    0,
    (
        pg_total_relation_size('access_audit_events')
        + pg_total_relation_size('audit_activity_projection')
        - :base_before_relation_bytes::bigint
    )::bigint,
    pg_wal_lsn_diff(pg_current_wal_insert_lsn(), :'base_before_wal_lsn')::bigint
from access_overhead_samples
where phase = 'without_required_access_audit';

call pg_temp.run_sensitive_ticket_detail_samples(
    'with_audit_warmup', true, 1, :'seed', :'base_time'::timestamptz, false
);

select
    pg_current_wal_insert_lsn()::text as wal_lsn,
    (
        pg_total_relation_size('access_audit_events')
        + pg_total_relation_size('audit_activity_projection')
    )::bigint as relation_bytes
\gset audit_before_

call pg_temp.run_sensitive_ticket_detail_samples(
    'with_required_access_audit',
    true,
    :access_repetitions,
    :'seed',
    :'base_time'::timestamptz,
    true
);

select
    count(*) as canonical_rows_written
from access_audit_events
where request_id like 'perf-overhead-with_required_access_audit-%'
\gset audit_

select
    count(*) as projection_rows_written
from audit_activity_projection
where request_id like 'perf-overhead-with_required_access_audit-%'
\gset projection_

select (
    :audit_canonical_rows_written::bigint = :access_repetitions::bigint
    and :projection_projection_rows_written::bigint = :access_repetitions::bigint
) as access_overhead_rows_valid
\gset

\if :access_overhead_rows_valid
\else
select 1 / 0 as access_overhead_row_amplification_mismatch;
\endif

insert into access_overhead_results
select
    'with_required_access_audit',
    count(*),
    round((percentile_cont(0.50) within group (order by elapsed_ms))::numeric, 3),
    round((percentile_cont(0.95) within group (order by elapsed_ms))::numeric, 3),
    round((count(*) * 1000.0 / sum(elapsed_ms))::numeric, 3),
    :audit_canonical_rows_written::bigint,
    :projection_projection_rows_written::bigint,
    :audit_canonical_rows_written::bigint + :projection_projection_rows_written::bigint,
    (
        pg_total_relation_size('access_audit_events')
        + pg_total_relation_size('audit_activity_projection')
        - :audit_before_relation_bytes::bigint
    )::bigint,
    pg_wal_lsn_diff(pg_current_wal_insert_lsn(), :'audit_before_wal_lsn')::bigint
from access_overhead_samples
where phase = 'with_required_access_audit';

select
    phase,
    samples,
    p50_ms,
    p95_ms,
    throughput_ops_per_second,
    canonical_rows_written,
    projection_rows_written,
    total_rows_written,
    relation_bytes_delta,
    wal_bytes_delta,
    round(total_rows_written::numeric / samples, 3) as rows_written_per_operation,
    round(relation_bytes_delta::numeric / samples, 3) as relation_bytes_per_operation,
    round(wal_bytes_delta::numeric / samples, 3) as wal_bytes_per_operation
from access_overhead_results
order by case phase
    when 'without_required_access_audit' then 1
    else 2
end;
