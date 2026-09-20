create or replace function staff_ticket_search_ngrams(document text)
returns text[]
language sql
immutable
strict
parallel safe
set search_path = pg_catalog
return (
    select coalesce(array_agg(token order by token), array[]::text[])
    from (
        select 'u:' || substr(document, positions.character_index, 1) as token
        from generate_series(1, char_length(document)) as positions(character_index)
        union
        select 'b:' || substr(document, positions.character_index, 2) as token
        from generate_series(1, greatest(char_length(document) - 1, 0)) as positions(character_index)
    ) tokens
);

drop index concurrently if exists ticket_search_documents_staff_ngram_idx;

create index concurrently ticket_search_documents_staff_ngram_idx
    on ticket_search_documents using gin (staff_ticket_search_ngrams(staff_document));
