\set ON_ERROR_STOP on

begin;

select rebuild_ticket_search_documents() as rebuilt_count;

select
    (select count(*) from tickets) as canonical_ticket_count,
    (select count(*) from ticket_search_documents) as projected_ticket_count,
    (select count(*) from tickets) =
        (select count(*) from ticket_search_documents) as counts_match;

commit;
