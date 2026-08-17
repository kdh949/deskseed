\set ON_ERROR_STOP on

select entity, actual_count, expected_count, actual_count = expected_count as matches_expected
from (
    select 'customers'::text as entity,
           (select count(*) from customers) as actual_count,
           :customer_count::bigint as expected_count
    union all
    select 'tickets',
           (select count(*) from tickets),
           :ticket_count::bigint
    union all
    select 'ticket_search_documents',
           (select count(*) from ticket_search_documents),
           :ticket_count::bigint
    union all
    select 'ticket_comments',
           (select count(*) from ticket_comments),
           :ticket_count::bigint * :comments_per_ticket::bigint
    union all
    select 'ticket_relations',
           (select count(*) from ticket_relations),
           floor(:ticket_count::numeric / 100)::bigint
    union all
    select 'ticket_audits',
           (select count(*) from ticket_audits),
           :ticket_audit_count::bigint
    union all
    select 'ticket_audit_events',
           (select count(*) from ticket_audit_events),
           :ticket_audit_count::bigint
    union all
    select 'access_audit_events',
           (select count(*) from access_audit_events),
           :access_audit_count::bigint
    union all
    select 'search_audit_details',
           (select count(*) from search_audit_details),
           floor(:access_audit_count::numeric / 10)::bigint
    union all
    select 'admin_security_audit_events',
           (select count(*) from admin_security_audit_events),
           :admin_audit_count::bigint
    union all
    select 'audit_activity_projection',
           (select count(*) from audit_activity_projection),
           :ticket_audit_count::bigint
               + :access_audit_count::bigint
               + :admin_audit_count::bigint
    union all
    select 'dangling_comment_ticket_refs',
           (
               select count(*)
               from ticket_comments comment
               left join tickets ticket on ticket.id = comment.ticket_id
               where ticket.id is null
           ),
           0::bigint
    union all
    select 'dangling_ticket_audit_refs',
           (
               select count(*)
               from ticket_audits audit
               left join tickets ticket on ticket.id = audit.ticket_id
               where ticket.id is null
           ),
           0::bigint
) fixture_counts
order by entity;
