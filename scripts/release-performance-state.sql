\set ON_ERROR_STOP on

select state.state, state.projected_count, state.last_rebuilt_at is not null as rebuild_recorded
from audit_activity_projection_state state
cross join lateral (
    select count(*) as actual_projected_count
    from audit_activity_projection
) projection_count
where state.id = 1
  and state.projected_count = projection_count.actual_projected_count;
