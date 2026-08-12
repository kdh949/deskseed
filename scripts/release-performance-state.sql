\set ON_ERROR_STOP on

select state, projected_count, last_rebuilt_at is not null as rebuild_recorded
from audit_activity_projection_state
cross join lateral (
    select count(*) as projected_count
    from audit_activity_projection
) projection_count
where id = 1;
