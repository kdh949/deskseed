create or replace function staff_ticket_search_bigrams(document text)
returns text[]
language sql
immutable
strict
parallel safe
set search_path = pg_catalog
return (
    select coalesce(array_agg(character || next_character order by ordinal), array[]::text[])
    from (
        select
            character,
            lead(character) over (order by ordinal) as next_character,
            ordinal
        from unnest(regexp_split_to_array(lower(document), ''))
            with ordinality as characters(character, ordinal)
    ) adjacent
    where next_character is not null
);

drop index concurrently if exists ticket_search_documents_staff_bigram_idx;

create index concurrently ticket_search_documents_staff_bigram_idx
    on ticket_search_documents using gin (staff_ticket_search_bigrams(staff_document));

drop index concurrently if exists ticket_search_documents_staff_character_idx;
