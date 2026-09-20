create or replace function staff_ticket_search_characters(document text)
returns text[]
language sql
immutable
strict
parallel safe
set search_path = pg_catalog
return regexp_split_to_array(lower(document), '');

drop index concurrently if exists ticket_search_documents_staff_ngram_idx;
drop index concurrently if exists ticket_search_documents_staff_character_idx;
drop function if exists staff_ticket_search_ngrams(text);

create index concurrently ticket_search_documents_staff_character_idx
    on ticket_search_documents using gin (staff_ticket_search_characters(staff_document));
