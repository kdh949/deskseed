\set ON_ERROR_STOP on

with queue_cardinality(query_name, eligible_rows) as (
    select
        'queue_my_open_first_page',
        count(*)
    from tickets t
    where t.status = 'OPEN'
      and t.assignee_id = md5(:'seed' || ':staff:42')::uuid

    union all

    select
        'queue_unassigned_my_groups_first_page',
        count(*)
    from tickets t
    where t.assignee_id is null
      and t.status not in ('SOLVED', 'CLOSED')
      and t.group_id in (
          select gm.group_id
          from group_memberships gm
          join support_groups mg on mg.id = gm.group_id and mg.status = 'ACTIVE'
          where gm.staff_id = md5(:'seed' || ':staff:42')::uuid
            and gm.status = 'ACTIVE'
      )

    union all

    select
        'queue_pending_first_page',
        count(*)
    from tickets t
    where t.status = 'PENDING'

    union all

    select
        'queue_recently_solved_first_page',
        count(*)
    from tickets t
    where t.status = 'SOLVED'
      and t.assignee_id = md5(:'seed' || ':staff:42')::uuid
      and t.updated_at >= :'base_time'::timestamptz - interval '30 days'

    union all

    select
        'queue_my_child_tasks_first_page',
        count(*)
    from tickets t
    where t.kind = 'INTERNAL_CHILD'
      and t.assignee_id = md5(:'seed' || ':staff:42')::uuid
      and t.status not in ('SOLVED', 'CLOSED')
)
select
    query_name,
    eligible_rows,
    least(eligible_rows, 51) as returned_first_page_rows,
    case
        when query_name in (
            'queue_unassigned_my_groups_first_page',
            'queue_my_child_tasks_first_page'
        ) then eligible_rows >= 51
        else eligible_rows > 0
    end as representative
from queue_cardinality
order by query_name;
