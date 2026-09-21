\set ON_ERROR_STOP on

\if :{?batch_size}
\else
  \set batch_size 100
\endif

select * from backfill_split_ticket_search_documents(:batch_size);
select * from ticket_search_projection_backfill_state where projection_version = 1;

-- This statement is intentionally informational on every batch. A completed backfill is
-- usable only when every missing/unexpected/duplicate count is zero.
select * from reconcile_split_ticket_search_documents();
