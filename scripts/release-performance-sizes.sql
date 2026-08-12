\set ON_ERROR_STOP on

select
    :'phase' as phase,
    category,
    object_name,
    bytes,
    pg_size_pretty(bytes) as pretty_size,
    index_scans
from (
    select
        'table_heap'::text as category,
        table_name::text as object_name,
        pg_relation_size(table_name::regclass) as bytes,
        null::bigint as index_scans
    from (
        values
            ('customers'),
            ('tickets'),
            ('ticket_comments'),
            ('ticket_audits'),
            ('ticket_audit_events'),
            ('access_audit_events'),
            ('admin_security_audit_events'),
            ('audit_activity_projection')
    ) tables(table_name)
    union all
    select
        'table_indexes',
        table_name,
        pg_indexes_size(table_name::regclass),
        null::bigint
    from (
        values
            ('customers'),
            ('tickets'),
            ('ticket_comments'),
            ('ticket_audits'),
            ('ticket_audit_events'),
            ('access_audit_events'),
            ('admin_security_audit_events'),
            ('audit_activity_projection')
    ) tables(table_name)
    union all
    select
        'candidate_index',
        candidate.index_name,
        coalesce(pg_relation_size(to_regclass(candidate.index_name)), 0),
        coalesce(stat.idx_scan, 0)
    from (
        values
            ('tickets_assignee_status_cursor_idx'),
            ('audit_activity_projection_actor_cursor_idx')
    ) candidate(index_name)
    left join pg_stat_user_indexes stat on stat.indexrelname = candidate.index_name
) measured
order by category, object_name;
