\set ON_ERROR_STOP on
\timing on

select command
from (
    values
        (
            'drop',
            'drop index if exists tickets_assignee_status_cursor_idx'
        ),
        (
            'drop',
            'drop index if exists audit_activity_projection_actor_cursor_idx'
        ),
        (
            'create',
            'create index tickets_assignee_status_cursor_idx '
                || 'on tickets (assignee_id, status, updated_at desc, ticket_number desc)'
        ),
        (
            'create',
            'create index audit_activity_projection_actor_cursor_idx '
                || 'on audit_activity_projection (actor_id, occurred_at desc, id desc)'
        )
) candidate(mode, command)
where mode = :'mode'
\gexec

analyze tickets;
analyze audit_activity_projection;
